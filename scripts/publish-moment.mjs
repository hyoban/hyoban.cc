import { fileURLToPath } from 'node:url'

import { runOpenCli } from './lib/opencli-runner.mjs'
import { runPublicationCommand } from './lib/publication-command.mjs'
import { PUBLICATION_PLATFORMS } from './lib/publication-platforms.mjs'

const root = fileURLToPath(new URL('../src/content/moments/', import.meta.url))
const argv = process.argv.slice(2)

if (argv.includes('--help') || argv.includes('-h')) {
  console.log(`Usage: pnpm moment:publish <moment-id> [options]

Publish exactly one canonical Moment to every Platform by default.

Options:
  --platform <name>  Target ${PUBLICATION_PLATFORMS.join(', ')}. Repeat for a subset.
  --execute          Perform external writes. Without it, only preview.
  -h, --help         Show this help.`)
  process.exit(0)
}

try {
  const result = await runPublicationCommand({
    argv,
    root,
    runOpenCli,
  })

  console.log(JSON.stringify(result, null, 2))

  if ((result.mode === 'preview' && !result.executable)
    || (result.mode === 'execute' && !result.successful)) {
    process.exitCode = 1
  }
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error))
  process.exitCode = 1
}
