import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'

import { runPublicationCommand } from './lib/publication-command.mjs'

const execFileAsync = promisify(execFile)
const root = fileURLToPath(new URL('../src/content/moments/', import.meta.url))
const argv = process.argv.slice(2)

if (argv.includes('--help') || argv.includes('-h')) {
  console.log(`Usage: pnpm moment:publish <moment-id> [options]

Publish exactly one canonical Moment to every Platform by default.

Options:
  --platform <name>  Target telegram, xiaohongshu, or x. Repeat for a subset.
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

async function runOpenCli(args) {
  try {
    const { stdout } = await execFileAsync('opencli', args, {
      encoding: 'utf8',
      maxBuffer: 50 * 1024 * 1024,
    })

    return JSON.parse(stdout)
  } catch (error) {
    const detail = [error.stderr, error.stdout, error.message]
      .find(value => typeof value === 'string' && value.trim())
      ?.trim()

    throw new Error(`OpenCLI command failed: ${detail ?? 'unknown error'}`, { cause: error })
  }
}
