import { mkdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { AstroIntegration } from 'astro'
import { loadEnv } from 'vite-plus'
import { getMomentFile } from '@hyoban/data-spec'
import { DATA_ROOT_VARIABLE } from './store.ts'

// With MOMENTS_API_TOKEN set, in the environment or .env, the site is built from the
// API rather than data/: before anything reads a record, this fetches the API's export
// once and writes it under .artifacts/api-data in data/'s layout, and points the store
// and the Moments collection there. Without a token everything reads data/, as the
// scripts always do. A token that fails to fetch fails the build instead of quietly
// falling back to the committed copy.

const DEFAULT_URL = 'https://hyoban-cc-api.hyoban.workers.dev'

interface Snapshot {
  revision: number
  manifest: unknown
  places: Record<string, unknown>
  trips: { id: string }[]
  moments: { uid: string; occurredAt: string }[]
}

export function apiData(): AstroIntegration {
  return {
    name: 'hyoban:api-data',
    hooks: {
      'astro:config:setup': async ({ command, config, logger }) => {
        const root = fileURLToPath(config.root)
        const env = loadEnv(command === 'dev' ? 'development' : 'production', root, '')
        const token = env['MOMENTS_API_TOKEN']

        if (!token) {
          logger.info('No MOMENTS_API_TOKEN, reading data/')
          return
        }

        const url = env['MOMENTS_API_URL'] || DEFAULT_URL
        const snapshot = await fetchSnapshot(url, token)
        const directory = join(root, '.artifacts', 'api-data')

        await writeSnapshot(snapshot, directory)
        process.env[DATA_ROOT_VARIABLE] = directory
        logger.info(
          `Read revision ${snapshot.revision} from ${url}: ${snapshot.moments.length} Moments, ` +
            `${Object.keys(snapshot.places).length} places, ${snapshot.trips.length} trips`,
        )
      },
    },
  }
}

async function fetchSnapshot(url: string, token: string): Promise<Snapshot> {
  const response = await fetch(new URL('/v1/export', url), {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(60_000),
  })

  if (!response.ok) {
    throw new Error(`The API's export failed: ${response.status} ${await response.text()}`)
  }

  return (await response.json()) as Snapshot
}

/** The snapshot as data/ keeps it; the store validates each record when it reads it. */
async function writeSnapshot(snapshot: Snapshot, directory: string) {
  const files: [string, unknown][] = [
    ['manifest.json', snapshot.manifest],
    ['places.json', snapshot.places],
    ...snapshot.trips.map((trip): [string, unknown] => [`trips/${trip.id}.json`, trip]),
    ...snapshot.moments.map((moment): [string, unknown] => [getMomentFile(moment), moment]),
  ]

  await rm(directory, { force: true, recursive: true })

  for (const [file, record] of files) {
    const path = join(directory, file)

    await mkdir(dirname(path), { recursive: true })
    await writeFile(path, `${JSON.stringify(record, null, 2)}\n`)
  }
}
