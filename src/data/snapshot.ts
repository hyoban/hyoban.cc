import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { getMomentFile } from '@hyoban/data-spec'
import { listJsonFiles } from './store.ts'

// The API's export, `GET /v1/export`, and how it lands on disk in data/'s layout: for
// the site's build (api-data.ts) and for the backup into data/ (`pnpm data:export`).

export const DEFAULT_API_URL = 'https://hyoban-cc-api.hyoban.workers.dev'

export interface Snapshot {
  revision: number
  manifest: unknown
  places: Record<string, unknown>
  trips: { id: string }[]
  moments: { uid: string; occurredAt: string }[]
  /** Each trip leg's route by its key; missing from an API that doesn't keep them yet. */
  routes?: Record<string, unknown>
}

export async function fetchSnapshot(url: string, token: string): Promise<Snapshot> {
  const response = await fetch(new URL('/v1/export', url), {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(60_000),
  })

  if (!response.ok) {
    throw new Error(`The API's export failed: ${response.status} ${await response.text()}`)
  }

  return (await response.json()) as Snapshot
}

/**
 * Writes the snapshot under `root` as data/ keeps it, in its canonical JSON, and removes
 * the Moments and trips no longer in it; anything else there, like devices.json, stays.
 * Returns the files it changed, relative to `root`. The store validates each record when
 * it reads it.
 */
export async function writeSnapshot(snapshot: Snapshot, root: string) {
  const files = new Map<string, unknown>([
    ['manifest.json', snapshot.manifest],
    ['places.json', snapshot.places],
    ...(snapshot.routes ? [['routes.json', snapshot.routes] as [string, unknown]] : []),
    ...snapshot.trips.map((trip): [string, unknown] => [`trips/${trip.id}.json`, trip]),
    ...snapshot.moments.map((moment): [string, unknown] => [getMomentFile(moment), moment]),
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

  for (const file of [...listJsonFiles('moments', root), ...listJsonFiles('trips', root)]) {
    if (!files.has(file)) {
      await rm(join(root, file))
      changed.push(file)
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
