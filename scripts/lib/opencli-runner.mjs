import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

export async function runOpenCli(args, options = {}) {
  const execute = options.execute ?? executeOpenCli

  try {
    const { stdout } = await execute(args)
    return JSON.parse(stdout)
  } catch (error) {
    const detail = [error.stderr, error.stdout, error.message]
      .find(value => typeof value === 'string' && value.trim())
      ?.trim()
    const wrapped = new Error(
      `OpenCLI command failed: ${detail ?? 'unknown error'}`,
      { cause: error },
    )
    const code = extractErrorCode(error)

    if (code) {
      wrapped.code = code
    }

    throw wrapped
  }
}

function executeOpenCli(args) {
  return execFileAsync('opencli', args, {
    encoding: 'utf8',
    maxBuffer: 50 * 1024 * 1024,
  })
}

function extractErrorCode(error) {
  for (const value of [error.stderr, error.stdout, error.message]) {
    if (typeof value !== 'string') {
      continue
    }

    const code = value.match(/(?:^|\n)\s*code:\s*([A-Z_]+)/)?.[1]

    if (code) {
      return code
    }
  }
}
