import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import {
  createMoment,
  readMoment,
  readMomentForPublication,
  readPublicationReceipts,
  writePublicationReceipt,
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

test('stores independent Publication Receipts beside their Moment', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'moment-repository-'))
  context.after(() => rm(root, { force: true, recursive: true }))
  const created = await createMoment(root, {
    now: new Date('2026-07-15T02:03:04.000Z'),
    slug: 'receipts',
  })
  const telegram = {
    externalId: '42',
    publishedAt: '2026-07-15T02:10:00.000Z',
    status: 'published',
    url: 'https://t.me/hyoban_travel/42',
  }
  const xiaohongshu = {
    attemptedAt: '2026-07-15T02:10:01.000Z',
    error: 'Session expired.',
    status: 'failed',
  }

  assert.deepEqual(await readPublicationReceipts(root, created.id), {})
  await writePublicationReceipt(root, created.id, 'telegram', telegram)
  await writePublicationReceipt(root, created.id, 'xiaohongshu', xiaohongshu)

  assert.deepEqual(await readPublicationReceipts(root, created.id), {
    telegram,
    xiaohongshu,
  })
  assert.deepEqual(
    JSON.parse(await readFile(join(created.directoryPath, 'publication-receipts.json'), 'utf8')),
    {
      platforms: { telegram, xiaohongshu },
      version: 1,
    },
  )
})

test('resolves Moment media into local publication inputs', async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'moment-repository-'))
  context.after(() => rm(root, { force: true, recursive: true }))
  const id = '2026/07/15-1003-media'
  const directory = join(root, id)
  await mkdir(directory, { recursive: true })
  await writeFile(join(directory, 'image-1.jpg'), 'image bytes')
  await writeFile(join(directory, 'index.md'), [
    '---',
    'publishedAt: "2026-07-15T10:03:04+08:00"',
    'media:',
    '  - type: image',
    '    file: "image-1.jpg"',
    '    alt: "A local image"',
    '---',
    '',
    'A moment with media.',
  ].join('\n'))

  const moment = await readMomentForPublication(root, id)

  assert.equal(moment.directoryPath, directory)
  assert.deepEqual(moment.media, [{
    alt: 'A local image',
    file: 'image-1.jpg',
    sourcePath: join(directory, 'image-1.jpg'),
    type: 'image',
  }])
})
