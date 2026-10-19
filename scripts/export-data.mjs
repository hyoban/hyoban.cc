import { fileURLToPath } from 'node:url'

import { refreshData } from './lib/data-sync.mjs'

// pnpm data:export
//
// Backs the API's records up into data/: fetches the export with MOMENTS_API_TOKEN, from
// the environment or .env, and writes it in data/'s layout and canonical JSON, removing
// the Moments and trips the API no longer has. devices.json and schema/ stay as they
// are. It stops when data/ has uncommitted changes the API may not have yet. Nothing is
// committed; the data-backup workflow does that.

const root = fileURLToPath(new URL('../', import.meta.url))
const { changed, snapshot } = await refreshData(root)

console.log(
  `Exported revision ${snapshot.revision}: ${snapshot.moments.length} Moments, ` +
    `${Object.keys(snapshot.places).length} places, ${snapshot.trips.length} trips; ` +
    `${changed.length === 0 ? 'data/ is up to date' : `${changed.length} files changed`}`,
)

for (const file of changed) {
  console.log(`  ${file}`)
}
