import { execFile as execFileCallback } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { promisify } from 'node:util'

import { momentRecordSchema, tripRecordSchema } from '@hyoban/data-spec'
import { collectionSchema, entrySchema } from '@hyoban/data-spec/entry'
import { buildMergeSql } from '../../apps/api/src/import.ts'
import { DEFAULT_API_URL, fetchSnapshot, writeSnapshot } from '../../apps/web/src/data/snapshot.ts'
import { listJsonFiles, readJson } from '../../apps/web/src/data/store.ts'
import { executeSql } from './d1.mjs'

// The API holds the records, and data/ is a working copy of them that git leaves out but
// for the car's: a script that writes data/ itself, like the create-moment skill, first
// refreshes data/ from the API and afterwards pushes what it wrote back; so does the Zeekr
// import, with the car's statistics. Pushing merges: it writes only the records it names,
// and the places they name, and never removes anything.

const execFile = promisify(execFileCallback)

/** The records the API holds that git leaves out, which a refresh would overwrite. */
const API_FILES = ['manifest.json', 'places.json', 'routes.json']
const API_DIRECTORIES = ['trips', 'moments', 'entries', 'collections', 'tracks']

/** The car's statistics, which git keeps and a refresh would overwrite too. */
const TRACKED_API_PATHS = [
  'data/zeekr/months',
  'data/zeekr/assisted-driving',
  'data/zeekr/captures',
]

/**
 * Each of the API's records in data/ by its digest, as the last export or push left it, so
 * a refresh can tell one written since; git can't, as it leaves them out. A record is its
 * file, a place `places.json#<id>`.
 */
const SYNCED_FILE = '.synced.json'

/**
 * Writes the API's records into data/, unless data/ has records written since the last
 * export or push, or changes to the car's git hasn't committed: those may not be in the
 * API yet, and a refresh would lose them.
 */
export async function refreshData(root) {
  const dataRoot = join(root, 'data')
  const { stdout } = await execFile('git', ['status', '--porcelain', '--', ...TRACKED_API_PATHS], {
    cwd: root,
  })
  const synced = readSynced(dataRoot)
  const unsynced = Object.entries(readRecordDigests(dataRoot))
    .filter(([record, value]) => synced[record] !== value)
    .map(([record]) => ` ? data/${record}`)
  const changes = [...stdout.trim().split('\n').filter(Boolean), ...unsynced]

  if (changes.length > 0) {
    throw new Error(
      `data/ has changes the API may not have yet; push them with pnpm data:push or remove them first:\n${changes.join('\n')}`,
    )
  }

  const snapshot = await fetchSnapshot(apiUrl(), apiToken(root))
  const changed = await writeSnapshot(snapshot, dataRoot)

  await writeSynced(dataRoot, readRecordDigests(dataRoot))

  return { changed, snapshot }
}

/** The digest of each of the API's records in data/ that git leaves out, by record. */
function readRecordDigests(dataRoot) {
  const digests = {}

  for (const file of API_FILES.filter((file) => existsSync(join(dataRoot, file)))) {
    if (file === 'places.json') {
      for (const [id, place] of Object.entries(readJson(file, dataRoot))) {
        digests[`places.json#${id}`] = digest(JSON.stringify(place))
      }
    } else {
      digests[file] = digest(readFileSync(join(dataRoot, file)))
    }
  }

  for (const file of API_DIRECTORIES.flatMap((directory) => listJsonFiles(directory, dataRoot))) {
    digests[file] = digest(readFileSync(join(dataRoot, file)))
  }

  return digests
}

function readSynced(dataRoot) {
  const path = join(dataRoot, SYNCED_FILE)

  return /** @type {Record<string, string>} */ (
    existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : {}
  )
}

async function writeSynced(dataRoot, synced) {
  const sorted = Object.fromEntries(
    Object.entries(synced).sort(([first], [second]) => first.localeCompare(second)),
  )

  await writeFile(join(dataRoot, SYNCED_FILE), `${JSON.stringify(sorted, null, 2)}\n`)
}

function digest(contents) {
  return createHash('sha256').update(contents).digest('hex')
}

/**
 * Writes these records from data/ into the API's database, Moments and trips or entries and
 * collections, with every place they name and the `places` given, leaving every other record
 * as it is. A collection goes with its marks, which its trip in the first shape is made from.
 */
export async function pushData(
  root,
  { moments = [], trips = [], entries = [], collections = [], places = [], vehicle = [] },
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
      ...readVehicle(vehicle, dataRoot),
    },
    new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    generator,
  )

  await executeSql(sql, { label: 'push', remote, dryRun })

  // What the API now has as data/ has it, so the next refresh may overwrite it.
  if (remote && !dryRun) {
    const digests = readRecordDigests(dataRoot)
    const pushed = [
      ...moments,
      ...trips,
      ...entries,
      ...collections,
      ...[...placeIds].map((id) => `places.json#${id}`),
    ]

    await writeSynced(dataRoot, {
      ...readSynced(dataRoot),
      ...Object.fromEntries(pushed.map((record) => [record, digests[record]])),
    })
  }

  // Written past the Worker, so the routes the new legs need are fetched now, and then
  // the site is built again with them, as the Worker's own writes have it.
  const routes = remote && !dryRun ? await fillRoutes(root) : undefined
  const built = remote && !dryRun ? await startBuild(root) : undefined

  return {
    moments: momentRecords.length + entryRecords.length,
    trips: tripRecords.length + collectionRecords.length,
    places: placeIds.size,
    vehicle: vehicle.length,
    routes,
    built,
  }
}

/**
 * Has the API call the site's deploy hook (`POST /v2/build`), for a write that went past
 * the Worker; whether a build started. A failure is only reported: the write stands.
 */
export async function startBuild(root) {
  try {
    const response = await fetch(new URL('/v2/build', apiUrl()), {
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
  const response = await fetch(new URL('/v2/routes', apiUrl()), {
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

/** The car's statistics among these files under data/, by kind, as the import takes them. */
export function readVehicle(files, dataRoot) {
  const of = (directory) =>
    files
      .filter((file) => file.startsWith(`zeekr/${directory}/`))
      .map((file) => readJson(file, dataRoot))

  return {
    vehicleMonths: of('months'),
    vehicleAssisted: of('assisted-driving'),
    vehicleCaptures: of('captures'),
  }
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
