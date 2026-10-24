import { fileURLToPath } from 'node:url'

import { buildImportSql } from '../apps/api/src/import.ts'
import { listJsonFiles, readJson } from '../src/data/store.ts'
import { startBuild } from './lib/data-sync.mjs'
import { executeSql } from './lib/d1.mjs'

// pnpm data:import [--remote] [--dry-run]
//
// Makes the API's D1 database hold exactly what data/ holds, to restore the backup:
// applies the migrations, writes every record, and marks deleted whatever data/ doesn't
// have. Local by default, the database `wrangler dev` uses; --remote writes the deployed
// one. --dry-run only writes the SQL under .artifacts/import. To add records without
// removing any, use pnpm data:push.

const root = fileURLToPath(new URL('../', import.meta.url))
const options = new Set(process.argv.slice(2))
const unknown = [...options].filter((option) => !['--remote', '--dry-run'].includes(option))

if (unknown.length > 0) {
  throw new Error(`Unknown option: ${unknown.join(' ')}`)
}

const dataRoot = `${root}data`
const sql = buildImportSql(
  {
    manifest: readJson('manifest.json', dataRoot),
    places: /** @type {Record<string, unknown>} */ (readJson('places.json', dataRoot)),
    trips: listJsonFiles('trips', dataRoot).map((file) => readJson(file, dataRoot)),
    moments: listJsonFiles('moments', dataRoot).map((file) => readJson(file, dataRoot)),
    routes: /** @type {Record<string, unknown>} */ (readJson('routes.json', dataRoot)),
  },
  new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
)

await executeSql(sql, {
  label: 'import',
  remote: options.has('--remote'),
  dryRun: options.has('--dry-run'),
  migrate: true,
})

// Written past the Worker, so the site is asked to build again from what it now holds.
if (options.has('--remote') && !options.has('--dry-run')) {
  console.log((await startBuild(root)) ? 'The site is building again.' : "The site isn't building.")
}
