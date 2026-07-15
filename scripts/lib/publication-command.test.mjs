import assert from 'node:assert/strict'
import { mkdtemp, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { createMoment } from './moment-repository.mjs'
import {
  parsePublicationArguments,
  runPublicationCommand,
} from './publication-command.mjs'

test('requires exactly one Moment and defaults to every Platform', () => {
  assert.deepEqual(parsePublicationArguments(['2026/07/15-1003-note']), {
    execute: false,
    momentId: '2026/07/15-1003-note',
    targets: ['telegram', 'xiaohongshu', 'x'],
  })
  assert.throws(() => parsePublicationArguments([]), /exactly one Moment id/)
  assert.throws(
    () => parsePublicationArguments(['first', 'second']),
    /exactly one Moment id/,
  )
})

test('accepts an explicit Platform subset and rejects unknown options', () => {
  assert.deepEqual(parsePublicationArguments([
    '2026/07/15-1003-note',
    '--platform',
    'x',
    '--platform',
    'telegram',
    '--execute',
  ]), {
    execute: true,
    momentId: '2026/07/15-1003-note',
    targets: ['x', 'telegram'],
  })
  assert.throws(
    () => parsePublicationArguments(['2026/07/15-1003-note', '--platform', 'bluesky']),
    /Unknown Platform: bluesky/,
  )
  assert.throws(
    () => parsePublicationArguments(['2026/07/15-1003-note', '--all']),
    /Unknown option: --all/,
  )
})

test('previews by default without invoking OpenCLI or writing receipts', async () => {
  const root = await mkdtemp(join(tmpdir(), 'moment-publish-preview-'))
  const moment = await createMoment(root, {
    now: new Date('2026-07-15T02:03:04.000Z'),
    slug: 'preview',
  })
  let calls = 0

  const result = await runPublicationCommand({
    argv: [moment.id, '--platform', 'telegram'],
    root,
    runOpenCli: async () => {
      calls += 1
      throw new Error('Preview must not invoke OpenCLI.')
    },
  })

  assert.equal(result.mode, 'preview')
  assert.equal(result.executable, true)
  assert.equal(calls, 0)
  await assert.rejects(
    readFile(join(moment.directoryPath, 'publication-receipts.json')),
    error => error.code === 'ENOENT',
  )
})

test('executes only with --execute and persists the Publication Receipt', async () => {
  const root = await mkdtemp(join(tmpdir(), 'moment-publish-execute-'))
  const moment = await createMoment(root, {
    now: new Date('2026-07-15T02:03:04.000Z'),
    slug: 'execute',
  })
  const calls = []

  const result = await runPublicationCommand({
    argv: [moment.id, '--platform', 'telegram', '--execute'],
    now: () => new Date('2026-07-15T02:10:00.000Z'),
    root,
    runOpenCli: async (args) => {
      calls.push(args)
      return [{
        id: '163',
        status: 'success',
        url: 'https://t.me/hyoban_travel/163',
      }]
    },
  })

  assert.equal(result.mode, 'execute')
  assert.equal(result.successful, true)
  assert.equal(calls.length, 1)
  assert.deepEqual(
    JSON.parse(await readFile(join(moment.directoryPath, 'publication-receipts.json'), 'utf8')),
    {
      platforms: {
        telegram: {
          externalId: '163',
          publishedAt: '2026-07-15T02:10:00.000Z',
          status: 'published',
          url: 'https://t.me/hyoban_travel/163',
        },
      },
      version: 1,
    },
  )
})
