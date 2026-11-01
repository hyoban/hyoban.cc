import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import {
  getMomentFile,
  type VehicleAssistedDrivingCapture,
  type VehicleCapture,
  type VehicleMonth,
} from '@hyoban/data-spec'
import {
  getCollectionFile,
  getEntryFile,
  getPublicTrackFile,
  type Collection,
  type Entry,
  type PublicTrack,
} from '@hyoban/data-spec/entry'
import { listJsonFiles } from './store.ts'

// The API's export, `GET /v2/export` (entries, collections and public tracks), and how it
// lands on disk in data/'s layout: for the site's build (api-data.ts) and for the copy in
// data/ (`pnpm data:export`).

export const DEFAULT_API_URL = 'https://hyoban-cc-api.hyoban.workers.dev'

export interface Snapshot {
  revision: number
  manifest: unknown
  places: Record<string, unknown>
  /** The first shape's, as an export from before entries had them. */
  trips?: { id: string }[]
  moments?: { uid: string; occurredAt: string }[]
  /** The second shape's, from `/v2/export`. */
  entries?: Pick<Entry, 'uid' | 'at'>[]
  collections?: Pick<Collection, 'uid' | 'slug'>[]
  tracks?: Pick<PublicTrack, 'uid'>[]
  /** Each trip leg's route by its key; missing from an API that doesn't keep them yet. */
  routes?: Record<string, unknown>
  /** The car's statistics; missing from an API that doesn't keep them yet. */
  vehicleMonths?: Pick<VehicleMonth, 'deviceId' | 'month'>[]
  vehicleAssisted?: Pick<VehicleAssistedDrivingCapture, 'deviceId' | 'source'>[]
  vehicleCaptures?: Pick<VehicleCapture, 'id'>[]
}

/** Where `data/` keeps the car's statistics, which the export brings when the API has them. */
const VEHICLE_DIRECTORIES = ['zeekr/months', 'zeekr/assisted-driving', 'zeekr/captures']

export async function fetchSnapshot(url: string, token: string): Promise<Snapshot> {
  const headers = { Authorization: `Bearer ${token}` }
  const response = await fetch(new URL('/v2/export', url), {
    headers,
    signal: AbortSignal.timeout(60_000),
  })

  if (!response.ok) {
    throw new Error(`The API's export failed: ${response.status} ${await response.text()}`)
  }

  return (await response.json()) as Snapshot
}

/**
 * Writes the snapshot under `root` as data/ keeps it, in its canonical JSON, and removes
 * the records no longer in it, and those of the other shape; anything else there, like the
 * car's expenses and the sources its statistics were captured from, stays.
 * Returns the files it changed, relative to `root`. The store validates each record when
 * it reads it.
 */
export async function writeSnapshot(snapshot: Snapshot, root: string) {
  const files = new Map<string, unknown>([
    ['manifest.json', snapshot.manifest],
    ['places.json', snapshot.places],
    ...(snapshot.routes ? [['routes.json', snapshot.routes] as [string, unknown]] : []),
    ...(snapshot.trips ?? []).map((trip): [string, unknown] => [`trips/${trip.id}.json`, trip]),
    ...(snapshot.moments ?? []).map((moment): [string, unknown] => [getMomentFile(moment), moment]),
    ...(snapshot.entries ?? []).map((entry): [string, unknown] => [getEntryFile(entry), entry]),
    ...(snapshot.collections ?? []).map((collection): [string, unknown] => [
      getCollectionFile(collection),
      collection,
    ]),
    ...(snapshot.tracks ?? []).map((track): [string, unknown] => [
      getPublicTrackFile(track),
      track,
    ]),
    ...(snapshot.vehicleMonths ?? []).map((month): [string, unknown] => [
      `zeekr/months/${month.deviceId}/${month.month}.json`,
      month,
    ]),
    ...(snapshot.vehicleAssisted ?? []).map((observation): [string, unknown] => [
      `zeekr/assisted-driving/${observation.deviceId}/${observation.source.captureId}.json`,
      observation,
    ]),
    ...(snapshot.vehicleCaptures ?? []).map((capture): [string, unknown] => [
      `zeekr/captures/${capture.id}.json`,
      capture,
    ]),
  ])
  const changed: string[] = []

  for (const [file, record] of files) {
    const path = join(root, file)
    const contents =
      file === 'routes.json'
        ? formatRoutes(record as Record<string, unknown>)
        : `${JSON.stringify(record, null, 2)}\n`

    if ((await readFile(path, 'utf8').catch(() => null)) !== contents) {
      await mkdir(dirname(path), { recursive: true })
      await writeFile(path, contents)
      changed.push(file)
    }
  }

  for (const directory of [
    'moments',
    'trips',
    'entries',
    'collections',
    'tracks',
    ...(snapshot.vehicleMonths ? VEHICLE_DIRECTORIES : []),
  ]) {
    for (const file of listJsonFiles(directory, root)) {
      if (!files.has(file)) {
        await rm(join(root, file))
        changed.push(file)
      }
    }
  }

  return changed
}

/** One route per line, by key, keeps routes.json diffable without spreading every point out. */
export function formatRoutes(routes: Record<string, unknown>) {
  const entries = Object.keys(routes)
    .sort()
    .map((key) => `  ${JSON.stringify(key)}: ${JSON.stringify(routes[key])}`)

  return entries.length > 0 ? `{\n${entries.join(',\n')}\n}\n` : '{}\n'
}
