import assert from 'node:assert/strict'
import test from 'node:test'

import { runOpenCli } from './opencli-runner.mjs'

test('parses OpenCLI JSON output', async () => {
  const result = await runOpenCli(['example', 'read'], {
    execute: async () => ({ stdout: '[{"id":"1"}]' }),
  })

  assert.deepEqual(result, [{ id: '1' }])
})

test('preserves a structured OpenCLI error code for write safety decisions', async () => {
  const failure = Object.assign(new Error('command failed'), {
    stdout: `ok: false
error:
  code: AUTH_REQUIRED
  message: Login expired.
  exitCode: 77
`,
  })

  await assert.rejects(
    runOpenCli(['example', 'write'], {
      execute: async () => {
        throw failure
      },
    }),
    error => error.code === 'AUTH_REQUIRED' && error.cause === failure,
  )
})
