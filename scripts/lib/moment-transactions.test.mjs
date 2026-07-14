import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { recoverMomentTransactions } from './moment-transactions.mjs'

test('recovers interrupted moment directory transactions', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'moment-transactions-'))
  const month = join(root, '2026', '07')
  await mkdir(month, { recursive: true })
  context.after(() => rm(root, { force: true, recursive: true }))

  const interrupted = 'xiaohongshu-interrupted'
  await writeDirectory(join(month, `.${interrupted}.backup`), 'old')
  await writeDirectory(join(month, `.${interrupted}.tmp`), 'new')

  const uncommitted = 'xiaohongshu-uncommitted'
  await writeDirectory(join(month, `.${uncommitted}.tmp`), 'new')

  const committed = 'xiaohongshu-committed'
  await writeDirectory(join(month, committed), 'new')
  await writeDirectory(join(month, `.${committed}.backup`), 'old')

  const result = await recoverMomentTransactions(root, 'xiaohongshu-')

  assert.deepEqual(result, {
    removedBackups: 1,
    removedTemporaries: 2,
    restoredBackups: 1,
  })
  assert.equal(await readIndex(join(month, interrupted)), 'old')
  assert.equal(await readIndex(join(month, committed)), 'new')
  assert.equal(await exists(join(month, `.${interrupted}.tmp`)), false)
  assert.equal(await exists(join(month, `.${uncommitted}.tmp`)), false)
  assert.equal(await exists(join(month, uncommitted)), false)
  assert.equal(await exists(join(month, `.${committed}.backup`)), false)
})

async function writeDirectory(directory, content) {
  await mkdir(directory)
  await writeFile(join(directory, 'index.md'), content)
}

async function readIndex(directory) {
  return readFile(join(directory, 'index.md'), 'utf8')
}

async function exists(path) {
  try {
    await stat(path)
    return true
  } catch (error) {
    if (error.code === 'ENOENT') {
      return false
    }

    throw error
  }
}
