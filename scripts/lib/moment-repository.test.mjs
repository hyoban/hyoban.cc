import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  createMoment,
  readMoment,
} from './moment-repository.mjs'

test('creates and reads a canonical Moment through one repository interface', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'moment-repository-'))
  context.after(() => rm(root, { force: true, recursive: true }))

  const created = await createMoment(root, {
    now: new Date('2026-07-15T02:03:04.000Z'),
    slug: 'Summer Walk',
  })
  const moment = await readMoment(root, created.id)

  assert.equal(created.id, '2026/07/15-1003-summer-walk')
  assert.equal(created.documentPath, join(root, '2026/07/15-1003-summer-walk/index.md'))
  assert.deepEqual(moment, {
    hidden: false,
    id: created.id,
    media: [],
    publishedAt: new Date('2026-07-15T02:03:04.000Z'),
    text: 'Write the moment here.',
  })
})
