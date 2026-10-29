import { execFile as execFileCallback } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { promisify } from 'node:util'

import { momentRecordSchema, tripRecordSchema } from '@hyoban/data-spec'
import { collectionSchema, entrySchema } from '@hyoban/data-spec/entry'
import { buildMergeSql } from '../../apps/api/src/import.ts'
import { DEFAULT_API_URL, fetchSnapshot, writeSnapshot } from '../../src/data/snapshot.ts'
import { listJsonFiles, readJson } from '../../src/data/store.ts'
import { executeSql } from './d1.mjs'

// The API holds the records and data/ backs them up, so a script that writes data/
// itself, like the create-moment skill, first refreshes data/ from the
// API and afterwards pushes what it wrote back. Pushing merges: it writes only the
// records it names, and the places they name, and never removes anything.

const execFile = promisify(execFileCallback)

/** The records the API holds, which a refresh would overwrite. */
const API_PATHS = [
  'data/manifest.json',
  'data/places.json',
  'data/trips',
  'data/moments',
  'data/entries',
  'data/collections',
  'data/tracks',
]

/**
 * Writes the API's records into data/, unless data/ has changes Git hasn't committed:
 * those may not be in the API yet, and a refresh would lose them.
 */
export async function refreshData(root) {
  const { stdout } = await execFile('git', ['status', '--porcelain', '--', ...API_PATHS], {
    cwd: root,
  })

  if (stdout.trim()) {
    throw new Error(
      `data/ has changes the API may not have yet; push them with pnpm data:push or discard them first:\n${stdout}`,
    )
  }

  const snapshot = await fetchSnapshot(apiUrl(), apiToken(root))
  const changed = await writeSnapshot(snapshot, join(root, 'data'))

  return { changed, snapshot }
}

/**
 * Writes these records from data/ into the API's database, Moments and trips or entries and
 * collections, with every place they name and the `places` given, leaving every other record
 * as it is. A collection goes with its marks, which its trip in the first shape is made from.
 */
export async function pushData(
  root,
  { moments = [], trips = [], entries = [], collections = [], places = [] },
  { generator, remote = true, dryRun = false },
) {
  const dataRoot = join(root, 'data')
  const momentRecords = moments.map((file) => momentRecordSchema.parse(readJson(file, dataRoot)))
  const tripRecords = trips.map((file) => tripRecordSchema.parse(readJson(file, dataRoot)))
  const entryRecords = entries.map((file) => entrySchema.parse(readJson(file, dataRoot)))
  const collectionRecords = collections.map((file) =>
    collectionSchema.parse(readJson(file, dataRoot)),
  )
  const collectionMarks = collectionRecords.length > 0 ? readMarks(dataRoot) : []
  const allPlaces = /** @type {Record<string, unknown>} */ (readJson('places.json', dataRoot))
  const placeIds = new Set([
    ...places,
    ...momentRecords.flatMap((moment) => moment.places),
    ...[...entryRecords, ...collectionMarks].flatMap((entry) => (entry.place ? [entry.place] : [])),
    ...collectionRecords.flatMap((collection) =>
      [...(collection.route?.straightLegs ?? []), ...(collection.route?.sameWayBack ?? [])].flatMap(
        (leg) => [leg.from, leg.to],
      ),
    ),
    ...tripRecords.flatMap((trip) => [
      ...trip.waypoints.map((waypoint) => waypoint.place),
      ...[...trip.straightLegs, ...trip.sameWayBack].flatMap((leg) => [leg.from, leg.to]),
    ]),
  ])
  const missing = [...placeIds].filter((id) => !allPlaces[id])

  if (missing.length > 0) {
    throw new Error(`data/places.json has no ${missing.join(', ')}.`)
  }

  const sql = buildMergeSql(
    {
      places: Object.fromEntries(
        [...placeIds]
          .sort((first, second) => first.localeCompare(second))
          .map((id) => [id, allPlaces[id]]),
      ),
      trips: trips.map((file) => readJson(file, dataRoot)),
      moments: moments.map((file) => readJson(file, dataRoot)),
      entries: [
        ...entries.map((file) => readJson(file, dataRoot)),
        ...collectionMarks.filter((mark) => !entryRecords.some((entry) => entry.uid === mark.uid)),
      ],
      collections: collections.map((file) => readJson(file, dataRoot)),
    },
    new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    generator,
  )

  await executeSql(sql, { label: 'push', remote, dryRun })

  // Written past the Worker, so the routes the new legs need are fetched now, and then
  // the site is built again with them, as the Worker's own writes have it.
  const routes = remote && !dryRun ? await fillRoutes(root) : undefined
  const built = remote && !dryRun ? await startBuild(root) : undefined

  return {
    moments: momentRecords.length + entryRecords.length,
    trips: tripRecords.length + collectionRecords.length,
    places: placeIds.size,
    routes,
    built,
  }
}

/**
 * Has the API call the site's deploy hook (`POST /v1/build`), for a write that went past
 * the Worker; whether a build started. A failure is only reported: the write stands.
 */
export async function startBuild(root) {
  try {
    const response = await fetch(new URL('/v1/build', apiUrl()), {
      method: 'POST',
      headers: { Authorization: `Bearer ${apiToken(root)}` },
      signal: AbortSignal.timeout(30_000),
    })

    if (!response.ok) {
      console.warn(`The API couldn't start a build: ${response.status} ${await response.text()}`)
      return false
    }

    return /** @type {{ started: boolean }} */ (await response.json()).started
  } catch (caught) {
    console.warn("The API couldn't start a build:", caught)
    return false
  }
}

/** Has the API fetch the routes the trips are missing, and returns what it did. */
async function fillRoutes(root) {
  const response = await fetch(new URL('/v1/routes', apiUrl()), {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiToken(root)}` },
    signal: AbortSignal.timeout(60_000),
  })

  if (!response.ok) {
    console.warn(
      `The API couldn't bring the routes up to date: ${response.status} ${await response.text()}`,
    )
    return undefined
  }

  return /** @type {{ fetched: number, removed: number, failed: number, left: number }} */ (
    await response.json()
  )
}

/** Every mark under data/entries, what the collections' trips in the first shape are made from. */
function readMarks(dataRoot) {
  return listJsonFiles('entries', dataRoot)
    .map((file) => entrySchema.parse(readJson(file, dataRoot)))
    .filter((entry) => Object.keys(entry.parts).length === 0)
}

function apiUrl() {
  return process.env.MOMENTS_API_URL || DEFAULT_API_URL
}

/** MOMENTS_API_TOKEN, from the environment or the repository's .env. */
export function apiToken(root) {
  if (!process.env.MOMENTS_API_TOKEN && existsSync(join(root, '.env'))) {
    process.loadEnvFile(join(root, '.env'))
  }

  if (!process.env.MOMENTS_API_TOKEN) {
    throw new Error('Set MOMENTS_API_TOKEN, in the environment or .env, to reach the API.')
  }

  return process.env.MOMENTS_API_TOKEN
}
