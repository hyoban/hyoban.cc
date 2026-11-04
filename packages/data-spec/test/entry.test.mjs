import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { test } from 'vite-plus/test'

import {
  collectionSchema,
  collectionToTrip,
  entrySchema,
  entryToMoment,
  getCollectionFacets,
  getDeviceIssues,
  getEntryAudience,
  getPartAudience,
  getPublicEntry,
  getStableUlid,
  getUnknownFields,
  getUploadEntry,
  getPluginModule,
  isInCollection,
  isMarkEntry,
  modules,
  momentToEntry,
  plugins,
  tripToCollection,
} from '../src/entry.ts'
import { tables } from '../src/tables.ts'
import { momentRecordSchema, tripRecordSchema } from '../src/index.ts'
import { readMomentRecords, readTripRecords } from '../../../apps/web/src/data/store.ts'

// The made-up data/ the tests read, in the first shape.
const fixtureRoot = fileURLToPath(new URL('fixtures/data/', import.meta.url))
const moments = readMomentRecords(fixtureRoot).map(({ record }) => record)
const trips = readTripRecords(fixtureRoot)

const at = '2026-10-29T12:00:00+08:00'
const base = { uid: getStableUlid(at, 'base'), at, createdAt: at, updatedAt: at }

test('every Moment becomes an entry and comes back the same', () => {
  for (const moment of moments) {
    const entry = entrySchema.parse(momentToEntry(moment))
    assert.deepEqual(momentRecordSchema.parse(entryToMoment(entry, moment.trip)), moment)
  }
})

test('every trip becomes a collection with marks and comes back the same', () => {
  for (const trip of trips) {
    const { collection, marks } = tripToCollection(trip, moments)
    const parsed = collectionSchema.parse(collection)
    const entries = marks.map((mark) => entrySchema.parse(mark))
    assert.equal(parsed.slug, trip.id)
    assert.equal(entries.length, trip.waypoints.length)
    assert.ok(entries.every(isMarkEntry))
    assert.deepEqual(tripRecordSchema.parse(collectionToTrip(parsed, entries)), trip)
  }
})

test('a migration names things the same each time', () => {
  for (const trip of trips) {
    assert.deepEqual(tripToCollection(trip, moments), tripToCollection(trip, moments))
  }

  assert.match(getStableUlid(at, 'x'), /^[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.notEqual(getStableUlid(at, 'x'), getStableUlid(at, 'y'))
})

test('a hidden Moment is private, a meal keeps its cost', () => {
  const moment = momentRecordSchema.parse({
    ...base,
    occurredAt: at,
    text: '沙茶面',
    hidden: true,
    food: { dishes: ['沙茶面'], verdict: 'good', bill: { amount: 32, people: 2 } },
  })
  const entry = momentToEntry(moment)
  assert.equal(getEntryAudience(entry), 'private')
  assert.deepEqual(entry.parts.food, {
    v: 1,
    dishes: ['沙茶面'],
    verdict: 'good',
    cost: 32,
    people: 2,
  })
  assert.equal(getEntryAudience({}), 'public')
})

test('a part follows its own say, then its container, then its plugin', () => {
  const entry = { visibility: undefined }
  assert.equal(getPartAudience(entry, 'text', {}), 'public')
  assert.equal(getPartAudience(entry, 'bill', {}), 'private')
  assert.equal(getPartAudience(entry, 'bill', {}, 'public'), 'public')
  assert.equal(getPartAudience(entry, 'bill', { visibility: 'private' }, 'public'), 'private')
  assert.equal(getPartAudience(entry, 'unknown', {}), 'public')
  assert.equal(
    getPartAudience({ visibility: 'private' }, 'bill', { visibility: 'public' }),
    'private',
  )
})

test('the public sees public parts, a bill only by its amount, category and note', () => {
  const entry = entrySchema.parse({
    ...base,
    parts: {
      text: { v: 1, text: '吃饭' },
      bill: {
        v: 1,
        visibility: 'public',
        amount: 32,
        currency: 'CNY',
        category: '餐饮',
        account: 'wechat',
        book: 'daily',
      },
      food: { v: 1, visibility: 'private', dishes: ['面'] },
    },
  })
  const shown = getPublicEntry(entry)
  assert.deepEqual(Object.keys(shown.parts).toSorted(), ['bill', 'text'])
  assert.equal(shown.parts.bill.account, undefined)
  assert.equal(shown.parts.bill.book, undefined)
  assert.equal(shown.parts.bill.amount, 32)

  const billOnly = entrySchema.parse({
    ...base,
    parts: { bill: { v: 1, amount: 9, currency: 'CNY' } },
  })
  assert.equal(getPublicEntry(billOnly), undefined)
  assert.equal(getPublicEntry({ ...entry, visibility: 'private' }), undefined)

  const mark = entrySchema.parse({ ...base, place: 'wuwei' })
  assert.deepEqual(getPublicEntry(mark), mark)
})

test('a public check-in shows its venue, never where it came from', () => {
  const entry = entrySchema.parse({
    ...base,
    point: { lat: 30.25, lng: 120.16, accuracy: 12, from: 'device' },
    parts: {
      checkin: {
        v: 1,
        venue: 'Noodle House',
        alias: '面馆',
        source: { app: 'swarm', checkin: 'c1', venue: 'v1' },
      },
    },
  })
  const shown = getPublicEntry(entry)
  assert.deepEqual(shown.parts.checkin, { v: 1, venue: 'Noodle House', alias: '面馆' })
  assert.equal(getPublicEntry({ ...entry, visibility: 'private' }), undefined)
  assert.throws(() =>
    entrySchema.parse({ ...base, parts: { checkin: { v: 1, venue: 'x', source: { app: 'x' } } } }),
  )
  assert.throws(() =>
    entrySchema.parse({
      ...base,
      parts: { checkin: { v: 1, venue: 'x', source: { app: 'swarm' } } },
    }),
  )
})

test("a check-in made from the phone's stay keeps when it ended", () => {
  const entry = entrySchema.parse({
    ...base,
    end: '2026-10-29T12:40:00+08:00',
    point: { lat: 30.25, lng: 120.16, from: 'track' },
    place: 'p-noodles',
    parts: { checkin: { v: 1, venue: '面馆', source: { app: 'phone' } } },
  })
  assert.equal(entry.parts.checkin.source.app, 'phone')
  assert.deepEqual(getPublicEntry(entry).parts.checkin, { v: 1, venue: '面馆' })
})

test('a private bill stays on the phone, a public one goes', () => {
  const entry = entrySchema.parse({
    ...base,
    parts: { text: { v: 1, text: '' }, bill: { v: 1, amount: 9, currency: 'CNY' } },
  })
  assert.equal(getUploadEntry(entry).parts.bill, undefined)
  assert.ok(getUploadEntry(entry, { bill: 'public' }).parts.bill)
  assert.ok(getUploadEntry(entry).parts.text)
})

test('a collection holds its span, its includes and not its excludes', () => {
  const collection = collectionSchema.parse({
    uid: getStableUlid(at, 'c'),
    kind: 'trip',
    title: '云南',
    span: { start: '2026-09-12', end: '2026-09-16' },
    include: [getStableUlid(at, 'in')],
    exclude: [getStableUlid(at, 'out')],
    createdAt: at,
    updatedAt: at,
  })
  assert.ok(isInCollection(collection, { uid: base.uid, at: '2026-09-16T23:00:00+08:00' }))
  assert.ok(!isInCollection(collection, { uid: base.uid, at: '2026-09-17' }))
  assert.ok(isInCollection(collection, { uid: getStableUlid(at, 'in'), at: '2025-01-01' }))
  assert.ok(!isInCollection(collection, { uid: getStableUlid(at, 'out'), at: '2026-09-13' }))
})

test('a collection adds up what its members spent, charged and ate', () => {
  const members = [
    entrySchema.parse({ ...base, parts: { bill: { v: 1, amount: 10.1, currency: 'CNY' } } }),
    entrySchema.parse({
      ...base,
      parts: { bill: { v: 1, amount: 0.2, currency: 'CNY' } },
      place: 'a',
    }),
    entrySchema.parse({ ...base, parts: { charge: { v: 1, energyWh: 30000, cost: 20 } } }),
    entrySchema.parse({ ...base, parts: { food: { v: 1, verdict: 'good' } }, place: 'a' }),
  ]
  assert.deepEqual(getCollectionFacets(members, [{ distance: 1234.5 }]), {
    spending: { amount: 10.3, currency: 'CNY', bills: 2 },
    charging: { sessions: 1, energyWh: 30000, cost: 20 },
    food: { meals: 1, good: 1 },
    places: { count: 1 },
    distance: { metres: 1235 },
  })
})

test('a device id names one device, and a charge names a device that is', () => {
  const device = (seed, id) =>
    entrySchema.parse({
      ...base,
      uid: getStableUlid(at, seed),
      parts: { device: { v: 1, id, name: 'Car', category: '汽车', price: 1 } },
    })
  const charge = (seed, vehicle) =>
    entrySchema.parse({
      ...base,
      uid: getStableUlid(at, seed),
      parts: { charge: { v: 1, energyWh: 1000, vehicle } },
    })
  const car = device('car', 'car')
  const again = device('again', 'car')
  const charged = charge('charged', 'car')
  const lost = charge('lost', 'bike')

  assert.deepEqual(getDeviceIssues([car, charged]), [])
  assert.deepEqual(
    getDeviceIssues([car, again, charged, lost]).map((issue) => issue.uid),
    [car.uid, again.uid, lost.uid],
  )
  assert.deepEqual(
    getDeviceIssues([car, again, lost], new Set([lost.uid])).map((issue) => issue.uid),
    [lost.uid],
  )
})

test('fields the spec does not name are found, in the parts it knows', () => {
  const entry = {
    ...base,
    visibilty: 'private',
    parts: {
      text: { v: 1, text: 'Hi', visibility: 'public' },
      charge: { v: 1, energyWh: 1000, durationMinutes: 9 },
      later: { v: 1, anything: true },
    },
  }

  assert.deepEqual(getUnknownFields(entry), ['visibilty', 'parts.charge.durationMinutes'])
  assert.deepEqual(getUnknownFields({ ...base, parts: {} }), [])
})

test('a module owns plugins the spec has, each in one module, and tables it keeps', () => {
  const owned = modules.flatMap((module) => module.plugins)
  assert.equal(new Set(owned).size, owned.length)
  for (const plugin of owned) assert.ok(plugin in plugins)
  for (const module of modules) {
    for (const table of module.tables) assert.ok(tables.some((each) => each.name === table))
  }
  assert.equal(getPluginModule('bill'), 'ledger')
  assert.equal(getPluginModule('subscription'), 'things')
  assert.equal(getPluginModule('text'), undefined)
  assert.equal(getPluginModule('unknown'), undefined)
})
