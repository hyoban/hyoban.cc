import { spawn } from 'node:child_process'
import { mkdir, writeFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

// Runs SQL against the API's D1 database through wrangler: the local one `wrangler dev`
// uses, or with `remote` the deployed one. The SQL is kept under .artifacts/import.

const root = fileURLToPath(new URL('../../', import.meta.url))

/** Writes `sql` under .artifacts/import, named by `label`, and runs it unless `dryRun`. */
export async function executeSql(sql, { label, remote = false, dryRun = false, migrate = false }) {
  const directory = join(root, '.artifacts', 'import')
  const file = join(
    directory,
    `${new Date()
      .toISOString()
      .replace(/\.\d{3}Z$/, 'Z')
      .replaceAll(':', '-')}-${label}.sql`,
  )

  await mkdir(directory, { recursive: true })
  await writeFile(file, sql)
  console.log(`Wrote ${relative(root, file)}`)

  if (dryRun) {
    return
  }

  const target = remote ? '--remote' : '--local'

  if (migrate) {
    await wrangler(['d1', 'migrations', 'apply', 'DB', target])
  }

  if (remote) {
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
