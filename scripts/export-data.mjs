import { fileURLToPath } from 'node:url'

import { refreshData } from './lib/data-sync.mjs'

// pnpm data:export
//
// Copies the API's records into data/, for the scripts that read or write them: fetches
// the export with MOMENTS_API_TOKEN, from the environment or .env, and writes it in data/'s
// layout and canonical JSON, removing the records the API no longer has. The car's expenses
// and sources, and schema/, stay as they are. It stops when data/ has records written since
// the last export or push, which the API may not have yet. Git leaves the copy out.

const root = fileURLToPath(new URL('../', import.meta.url))
const { changed, snapshot } = await refreshData(root)

console.log(
  `Exported revision ${snapshot.revision}: ` +
    (snapshot.entries
      ? `${snapshot.entries.length} entries, ${snapshot.collections?.length ?? 0} collections, ${snapshot.tracks?.length ?? 0} tracks, `
      : `${snapshot.moments?.length ?? 0} Moments, ${snapshot.trips?.length ?? 0} trips, `) +
    `${Object.keys(snapshot.places).length} places; ` +
    `${changed.length === 0 ? 'data/ is up to date' : `${changed.length} files changed`}`,
)

for (const file of changed) {
  console.log(`  ${file}`)
}
