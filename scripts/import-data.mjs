import { spawn } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

import { buildImportSql } from '../apps/api/src/import.ts'
import { listJsonFiles, readJson } from '../src/data/store.ts'

// pnpm data:import [--remote] [--dry-run]
//
// Makes the API's D1 database hold what data/ holds: applies the migrations, then runs
// the SQL apps/api/src/import.ts builds from the records. Local by default, the
// database `wrangler dev` uses; --remote writes the deployed one. --dry-run only
// writes the SQL under .artifacts/import.

const root = fileURLToPath(new URL('../', import.meta.url))
const options = new Set(process.argv.slice(2))
const unknown = [...options].filter((option) => !['--remote', '--dry-run'].includes(option))

if (unknown.length > 0) {
  throw new Error(`Unknown option: ${unknown.join(' ')}`)
}

const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
const sql = buildImportSql(
  {
    manifest: readJson('manifest.json'),
    places: /** @type {Record<string, unknown>} */ (readJson('places.json')),
    trips: listJsonFiles('trips').map((file) => readJson(file)),
    moments: listJsonFiles('moments').map((file) => readJson(file)),
  },
  now,
)
const directory = join(root, '.artifacts', 'import')
const file = join(directory, `${now.replaceAll(':', '-')}.sql`)

await mkdir(directory, { recursive: true })
await writeFile(file, sql)
console.log(`Wrote ${relative(root, file)}`)

if (!options.has('--dry-run')) {
  const target = options.has('--remote') ? '--remote' : '--local'

  await wrangler(['d1', 'migrations', 'apply', 'DB', target])
  if (target === '--remote') {
    await wrangler(['d1', 'execute', 'DB', target, '--file', file, '--yes'])
  } else {
    // Locally the result of every statement is printed; only how many ran is worth showing.
    const results = JSON.parse(
      await wrangler(['d1', 'execute', 'DB', target, '--file', file, '--yes', '--json'], {
        quiet: true,
      }),
    )
    console.log(`Ran ${results.length} statements locally`)
  }
}

/** Runs wrangler against the API's config, with its prompts on this terminal; quiet keeps its output to return. */
function wrangler(args, { quiet = false } = {}) {
  const child = spawn(
    join(root, 'node_modules/.bin/wrangler'),
    [...args, '--config', join(root, 'apps/api/wrangler.jsonc')],
    { stdio: ['inherit', quiet ? 'pipe' : 'inherit', 'inherit'] },
  )
  let output = ''

  child.stdout?.on('data', (chunk) => {
    output += chunk
  })

  return new Promise((resolve, reject) => {
    child.on('error', reject)
    child.on('exit', (code) =>
      code === 0 ? resolve(output) : reject(new Error(`wrangler ${args[0]} exited with ${code}`)),
    )
  })
}
