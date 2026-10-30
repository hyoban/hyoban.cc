import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { test } from 'vite-plus/test'

import { tripVideoMusic } from '../../apps/web/src/data/music.ts'
import { getMediaKey, getMomentFile, momentRecordSchema, tripRecordSchema } from '@hyoban/data-spec'
import { getEntryFile } from '@hyoban/data-spec/entry'
import {
  dataRoot,
  readDevices,
  readManifest,
  readMomentRecords,
  readPlaces,
  readTripRecords,
} from '../../apps/web/src/data/store.ts'
import { getDataSchemas, getSchemaFile, renderSchema } from './data-schema.mjs'
import { getUlidTime, ulid } from './ulid.mjs'

// data/ is what the recording app writes and the site reads; these keep it whole.

const manifest = readManifest()
const places = readPlaces()
const trips = readTripRecords()
const moments = readMomentRecords()
const devices = readDevices()

test('every record under data/ fits the spec and sits where its name says', () => {
  assert.ok([2, 3].includes(manifest.schemaVersion))
  assert.ok(Object.keys(places).length > 0)
  assert.ok(trips.length > 0)
  assert.ok(moments.length > 0)
  assert.ok(Object.keys(devices).length > 0)

  for (const { file, record } of moments) {
    assert.equal(
      file,
      file.startsWith('entries/')
        ? getEntryFile({ at: record.occurredAt, uid: record.uid })
        : getMomentFile(record),
      `Moment ${record.uid} is in the wrong file`,
    )
    assert.ok(
      record.createdAt <= record.updatedAt,
      `Moment ${record.uid} was updated before it was created`,
    )
  }
})

test('uids are unique, of the Moments and of their media', () => {
  const uids = moments.flatMap(({ record }) => [
    record.uid,
    ...record.media.map((media) => media.uid),
  ])
  assert.equal(new Set(uids).size, uids.length)
})

test('places, trips, covers and music referenced by records exist', () => {
  const momentsByUid = new Map(moments.map(({ record }) => [record.uid, record]))
  const tripIds = new Set(trips.map((trip) => trip.id))

  for (const { record } of moments) {
    for (const place of record.places) {
      assert.ok(places[place], `Moment ${record.uid} names unknown place ${place}`)
    }

    if (record.trip) {
      assert.ok(tripIds.has(record.trip), `Moment ${record.uid} names unknown trip ${record.trip}`)
    }
  }

  for (const trip of trips) {
    const tripPlaces = [
      ...trip.waypoints.map((waypoint) => waypoint.place),
      ...trip.straightLegs.flatMap((leg) => [leg.from, leg.to]),
      ...trip.sameWayBack.flatMap((leg) => [leg.from, leg.to]),
    ]

    for (const place of tripPlaces) {
      assert.ok(places[place], `Trip ${trip.id} names unknown place ${place}`)
    }

    if (trip.music) {
      assert.ok(
        tripVideoMusic.some((track) => track.id === trip.music),
        `Trip ${trip.id} names unknown music ${trip.music}`,
      )
    }

    for (const cover of trip.covers) {
      const moment = momentsByUid.get(cover.moment)
      const media = moment?.media.find((item) => item.uid === cover.media)
      assert.ok(
        media,
        `Trip ${trip.id} cover ${cover.moment}/${cover.media} isn't a Moment's media`,
      )
      assert.ok(
        moment.occurredAt.slice(0, 10) >= trip.start && moment.occurredAt.slice(0, 10) <= trip.end,
        `Trip ${trip.id} cover ${cover.moment} is outside the trip`,
      )
    }
  }
})

test('the committed JSON Schema matches @hyoban/data-spec', async () => {
  for (const [name, schema] of Object.entries(getDataSchemas())) {
    const committed = await readFile(join(dataRoot, 'schema', getSchemaFile(name)), 'utf8')
    assert.equal(committed, renderSchema(name, schema), `Run pnpm data:schema: ${name} is stale`)
  }
})

test("takes a published Moment on any day, its media under its uid's directory", () => {
  const base = {
    uid: ulid(),
    occurredAt: '2026-10-01T17:40:00+08:00',
    text: '',
    createdAt: '2026-10-01T17:41:00+08:00',
    updatedAt: '2026-10-01T17:41:00+08:00',
  }
  const published = { at: base.createdAt }
  const photo = (key) => ({
    uid: ulid(),
    type: 'image',
    width: 10,
    height: 10,
    published: {
      key,
      contentType: 'image/webp',
      bytes: 1,
      etag: '0'.repeat(32),
      width: 10,
      height: 10,
    },
  })
  const mediaUid = ulid()

  assert.ok(momentRecordSchema.safeParse({ ...base, published }).success)
  assert.ok(momentRecordSchema.safeParse({ ...base, occurredAt: '2026-10-02', published }).success)
  assert.equal(
    getMediaKey(base.uid, mediaUid, 'webp', '960w'),
    `moments/${base.uid.toLowerCase()}/${mediaUid.toLowerCase()}-960w.webp`,
  )
  assert.ok(
    momentRecordSchema.safeParse({
      ...base,
      published,
      media: [photo(getMediaKey(base.uid, mediaUid, 'webp'))],
    }).success,
  )
  assert.ok(
    !momentRecordSchema.safeParse({
      ...base,
      published,
      media: [photo(getMediaKey(ulid(), mediaUid, 'webp'))],
    }).success,
  )
  assert.ok(!momentRecordSchema.safeParse({ ...base, places: ['nanjing', 'nanjing'] }).success)
  assert.ok(!momentRecordSchema.safeParse({ ...base, occurredAt: '2026-10-01T17:40:00' }).success)
})

test("keeps fields it doesn't know and fills the defaults", () => {
  const record = momentRecordSchema.parse({
    uid: ulid(),
    occurredAt: '2026-10-01',
    text: 'Words.',
    mood: 'sunny',
    createdAt: '2026-10-01T17:41:00+08:00',
    updatedAt: '2026-10-01T17:41:00+08:00',
  })

  assert.deepEqual(record.places, [])
  assert.deepEqual(record.media, [])
  assert.equal(record.hidden, false)
  assert.equal(record.mood, 'sunny')
  assert.ok(
    !tripRecordSchema.safeParse({
      id: 'trip',
      title: 'Trip',
      start: '2026-10-02',
      end: '2026-10-01',
      createdAt: record.createdAt,
      updatedAt: record.updatedAt,
    }).success,
  )
})

test('makes ULIDs that sort by time and give it back', () => {
  const time = Date.parse('2026-10-01T17:40:00+08:00')
  const first = ulid(time)
  const second = ulid(time + 1)

  assert.match(first, /^[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.equal(getUlidTime(first), time)
  assert.ok(first < second)
  assert.notEqual(ulid(time), first)
})
