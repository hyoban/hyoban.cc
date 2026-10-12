import assert from 'node:assert/strict'
import { cp, mkdir, mkdtemp, readdir, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'vite-plus/test'

import { getMomentFile } from '../../src/data/spec.ts'
import {
  readManifest,
  readMomentRecords,
  readPlaces,
  readTripRecords,
} from '../../src/data/store.ts'
import { planPlaces, publishBundle, readBundle, writeReceipt } from './publish-data.mjs'
import { ulid } from './ulid.mjs'

const fixture = fileURLToPath(new URL('../fixtures/bundle/', import.meta.url))
const now = '2026-10-07T09:12:00+08:00'
const nanjing = { name: '南京', latitude: 32.0603, longitude: 118.7969 }

/** A repository with one published Moment, and the sample bundle with one photo in media/. */
async function setUp() {
  const root = await mkdtemp(join(tmpdir(), 'hyoban-publish-test-'))
  const dataRoot = join(root, 'data')
  const bundleDirectory = join(root, 'bundle')
  const existing = {
    uid: ulid(Date.parse('2026-10-01T10:00:00+08:00')),
    occurredAt: '2026-10-01T10:00:00+08:00',
    places: ['nanjing'],
    text: '出发。',
    hidden: false,
    media: [],
    published: { id: '2026/10/01-01-nanjing', slug: 'nanjing', order: 1, at: now },
    createdAt: '2026-10-01T10:01:00+08:00',
    updatedAt: '2026-10-01T10:01:00+08:00',
  }

  await cp(fixture, bundleDirectory, { recursive: true })
  await writeJson(join(dataRoot, 'manifest.json'), {
    schemaVersion: 1,
    generator: 'test',
    updatedAt: now,
  })
  await writeJson(join(dataRoot, 'places.json'), { nanjing })
  await writeJson(join(dataRoot, getMomentFile(existing)), existing)

  const bundle = await readBundle(bundleDirectory)
  const [withPhoto] = bundle.moments.filter(
    ({ record }) => record.media.length > 0 && record.places.length === 2,
  )
  await mkdir(join(bundleDirectory, 'media'), { recursive: true })
  await writeFile(
    join(bundleDirectory, 'media', `${withPhoto.record.media[0].uid}.jpg`),
    'not a jpeg',
  )

  return { bundleDirectory, dataRoot, existing }
}

/** Stands in for the media pipeline: a published block from the stem and the item's size. */
function fakePrepareMedia(calls) {
  return async ({ files, item, momentId, stem }) => {
    calls.push({ files, momentId, stem, uid: item.uid })

    return {
      key: `moments/${momentId}/${stem}.webp`,
      contentType: 'image/webp',
      bytes: 1,
      etag: '0'.repeat(32),
      width: item.width,
      height: item.height,
      variants: [],
    }
  }
}

test("matches the bundle's places to the repository's by distance or name", () => {
  const plan = planPlaces(
    {
      'nanjing-2': { name: '南京', latitude: 32.0607, longitude: 118.7972 },
      'nanjing-east': { name: '南京东', latitude: 32.0604, longitude: 118.797 },
      nanjing: { name: '南京', latitude: 40, longitude: 118.7969 },
      yantai: { name: '烟台', latitude: 37.46, longitude: 121.45 },
    },
    { nanjing },
  )

  assert.deepEqual(plan.renamedPlaces, { 'nanjing-2': 'nanjing', 'nanjing-east': 'nanjing' })
  assert.deepEqual(Object.keys(plan.places), ['nanjing', 'yantai'])
  assert.deepEqual(plan.places.nanjing, nanjing)
  assert.equal(plan.conflicts.length, 1)
})

test("publishes a bundle: renames places, assigns ids and orders, prepares media, skips what it can't", async () => {
  const { bundleDirectory, dataRoot, existing } = await setUp()
  const calls = []
  const result = await publishBundle(await readBundle(bundleDirectory), {
    dataRoot,
    now,
    prepareMedia: fakePrepareMedia(calls),
  })

  assert.deepEqual(result.renamedPlaces, { 'nanjing-2': 'nanjing' })
  assert.deepEqual(result.conflicts, [])
  assert.equal(result.skipped.length, 1)
  assert.match(result.skipped[0], /no file in media\//)

  const places = readPlaces(dataRoot)
  assert.deepEqual(Object.keys(places), ['nanjing', 'yantai-port'])
  assert.deepEqual(places.nanjing, nanjing)
  assert.equal(places['yantai-port'].note, '轮渡码头')

  const moments = readMomentRecords(dataRoot).map(({ record }) => record)
  assert.equal(moments.length, 3)
  const [first, withPhoto, proposed] = moments
  assert.equal(first.uid, existing.uid)
  assert.deepEqual(withPhoto.places, ['nanjing', 'yantai-port'])
  assert.deepEqual(withPhoto.published, {
    id: '2026/10/01-02-nanjing',
    slug: 'nanjing',
    order: 2,
    at: now,
  })
  assert.equal(withPhoto.media[0].published.key, 'moments/2026/10/01-02-nanjing/nanjing.webp')
  assert.equal(withPhoto.createdAt, '2026-10-01T17:41:03+08:00')
  assert.deepEqual(proposed.published, {
    id: '2026/10/02-01-ferry-morning',
    slug: 'ferry-morning',
    order: 1,
    at: now,
  })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].stem, 'nanjing')
  assert.ok(calls[0].files.image.endsWith('.jpg'))
  assert.deepEqual(result.blankAlt, [`2026/10/01-02-nanjing ${withPhoto.media[0].uid}`])

  const [trip] = readTripRecords(dataRoot)
  assert.equal(trip.id, '2026-fixture-trip')
  assert.deepEqual(trip.waypoints, [{ at: '2026-10-01T08:00:00+08:00', place: 'nanjing' }])
  assert.deepEqual(trip.straightLegs, [{ from: 'nanjing', to: 'yantai-port', by: 'train' }])
  assert.equal(readManifest(dataRoot).generator, 'hyoban.cc data:publish')

  const receiptDirectory = join(bundleDirectory, '..', 'receipt')
  await writeReceipt(result.receipt, receiptDirectory)
  const receiptManifest = JSON.parse(
    await readFile(join(receiptDirectory, 'manifest.json'), 'utf8'),
  )
  assert.deepEqual(receiptManifest.renamedPlaces, { 'nanjing-2': 'nanjing' })
  assert.deepEqual(
    Object.keys(JSON.parse(await readFile(join(receiptDirectory, 'places.json'), 'utf8'))),
    ['nanjing', 'yantai-port'],
  )
  assert.deepEqual(
    (await readdir(join(receiptDirectory, 'moments'), { recursive: true }))
      .filter((file) => file.endsWith('.json'))
      .sort(),
    [withPhoto, proposed].map((record) => getMomentFile(record).slice('moments/'.length)).sort(),
  )
  assert.ok(!(await readdir(receiptDirectory)).includes('media'))

  // Publishing the same bundle again, before the app took the receipt, changes nothing
  // and prepares nothing twice.
  const again = await publishBundle(await readBundle(bundleDirectory), {
    dataRoot,
    now: '2026-10-08T09:00:00+08:00',
    prepareMedia: fakePrepareMedia(calls),
  })
  assert.equal(calls.length, 1)
  assert.deepEqual(
    readMomentRecords(dataRoot).map(({ record }) => record),
    moments,
  )
  assert.equal(again.moments.length, 2)
})

test('refuses a bundle from another schema version', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hyoban-publish-test-'))
  await writeJson(join(root, 'manifest.json'), {
    schemaVersion: 2,
    generator: 'future',
    updatedAt: now,
  })

  await assert.rejects(() => readBundle(root), /schema version/)
})

async function writeJson(path, value) {
  await mkdir(join(path, '..'), { recursive: true })
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`)
}
