import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { DEFAULT_API_URL, fetchSnapshot, writeSnapshot } from '../src/data/snapshot.ts'

// pnpm data:export
//
// Backs the API's records up into data/: fetches the export with MOMENTS_API_TOKEN, from
// the environment or .env, and writes it in data/'s layout and canonical JSON, removing
// the Moments and trips the API no longer has. devices.json and schema/ stay as they
// are. Nothing is committed; the data-backup workflow does that.

const root = fileURLToPath(new URL('../', import.meta.url))

if (existsSync(join(root, '.env'))) {
  process.loadEnvFile(join(root, '.env'))
}

const token = process.env.MOMENTS_API_TOKEN

if (!token) {
  throw new Error('Set MOMENTS_API_TOKEN to export the API into data/.')
}

const url = process.env.MOMENTS_API_URL || DEFAULT_API_URL
const snapshot = await fetchSnapshot(url, token)
const changed = await writeSnapshot(snapshot, join(root, 'data'))

console.log(
  `Exported revision ${snapshot.revision} from ${url}: ${snapshot.moments.length} Moments, ` +
    `${Object.keys(snapshot.places).length} places, ${snapshot.trips.length} trips; ` +
    `${changed.length === 0 ? 'data/ is up to date' : `${changed.length} files changed`}`,
)

for (const file of changed) {
  console.log(`  ${file}`)
}
