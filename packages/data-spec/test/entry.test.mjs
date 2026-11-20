import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { test } from 'vite-plus/test'

import {
  collectionSchema,
  collectionToTrip,
  entrySchema,
  entryToMoment,
  foodToCost,
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
  isPlannedEntry,
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

test("a trip's public check-ins with a place are stops on its way, private ones and Moments not", () => {
  const collection = collectionSchema.parse({
    uid: getStableUlid(at, 'trip'),
    kind: 'trip',
    title: '海边',
    span: { start: '2026-10-29T08:00:00+08:00', end: '2026-10-30' },
    createdAt: at,
    updatedAt: at,
  })
  const checkin = (seed, time, place, extra = {}) =>
    entrySchema.parse({
      ...base,
      uid: getStableUlid(time, seed),
      at: time,
      place,
      parts: { checkin: { v: 1, venue: place, source: { app: 'phone' } } },
      ...extra,
    })
  const entries = [
    checkin('before', '2026-10-29T07:00:00+08:00', 'home'),
    checkin('station', '2026-10-29T09:00:00+08:00', 'station'),
    checkin('hidden', '2026-10-29T10:00:00+08:00', 'secret', { visibility: 'private' }),
    checkin('words', '2026-10-29T11:00:00+08:00', 'cafe', {
      parts: {
        checkin: { v: 1, venue: 'cafe', source: { app: 'phone' } },
        text: { v: 1, text: '咖啡' },
      },
    }),
    checkin('beach', '2026-10-30T15:00:00+08:00', 'beach'),
  ]

  assert.deepEqual(
    collectionToTrip(collection, entries).waypoints.map((waypoint) => waypoint.place),
    ['station', 'beach'],
  )
})

test('a migration names things the same each time', () => {
  for (const trip of trips) {
    assert.deepEqual(tripToCollection(trip, moments), tripToCollection(trip, moments))
  }

  assert.match(getStableUlid(at, 'x'), /^[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.notEqual(getStableUlid(at, 'x'), getStableUlid(at, 'y'))
})

test('a hidden Moment is private, a meal is a cost of the kind food', () => {
  const moment = momentRecordSchema.parse({
    ...base,
    occurredAt: at,
    text: '沙茶面',
    hidden: true,
    food: { dishes: ['沙茶面'], verdict: 'good', bill: { amount: 32, people: 2 } },
  })
  const entry = momentToEntry(moment)
  assert.equal(getEntryAudience(entry), 'private')
  assert.equal(entry.parts.food, undefined)
  assert.deepEqual(entry.parts.cost, {
    v: 1,
    kind: 'food',
    items: ['沙茶面'],
    verdict: 'good',
    amount: 32,
    people: 2,
  })
  assert.deepEqual(entryToMoment(entry).food, moment.food)
  assert.equal(getEntryAudience({}), 'public')
})

test("a cost that isn't a meal is the Moment's own, and a meal kept the old way reads as a cost", () => {
  const moment = momentRecordSchema.parse({
    ...base,
    occurredAt: at,
    text: '进了博物馆',
    cost: { items: ['门票'], verdict: 'good', amount: 60, currency: 'HKD', people: 2 },
  })
  const entry = momentToEntry(moment)
  assert.deepEqual(entry.parts.cost, {
    v: 1,
    items: ['门票'],
    verdict: 'good',
    amount: 60,
    currency: 'HKD',
    people: 2,
  })
  assert.deepEqual(entryToMoment(entry).cost, moment.cost)
  assert.equal(entryToMoment(entry).food, undefined)

  const old = { v: 1, dishes: ['面'], verdict: 'ok', cost: 9, people: 1 }
  assert.deepEqual(foodToCost(old), {
    v: 1,
    kind: 'food',
    items: ['面'],
    verdict: 'ok',
    amount: 9,
    people: 1,
  })
  const older = entryToMoment({ ...entry, parts: { text: { v: 1, text: '面' }, food: old } })
  assert.deepEqual(older.food, { dishes: ['面'], verdict: 'ok', bill: { amount: 9, people: 1 } })
  assert.equal(older.cost, undefined)

  const hidden = entryToMoment({
    ...entry,
    parts: { ...entry.parts, cost: { ...entry.parts.cost, visibility: 'private' } },
  })
  assert.equal(hidden.cost, undefined)
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

  // A public bill goes by its public fields alone, even when sent whole.
  const whole = entrySchema.parse({
    ...base,
    parts: {
      bill: {
        v: 1,
        visibility: 'public',
        amount: 9,
        currency: 'CNY',
        account: 'wechat',
        book: 'daily',
      },
      cost: { v: 1, kind: 'food', items: ['面'], amount: 9 },
    },
  })
  assert.deepEqual(getUploadEntry(whole).parts.bill, {
    v: 1,
    visibility: 'public',
    kind: 'expense',
    amount: 9,
    currency: 'CNY',
  })
  assert.deepEqual(getUploadEntry(whole).parts.cost, whole.parts.cost)
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
  assert.equal(getPluginModule('occasion'), 'occasions')
  assert.equal(getPluginModule('text'), undefined)
  assert.equal(getPluginModule('unknown'), undefined)
})

test('an occasion is on the day it started, public with every field, and once without a repeat', () => {
  const entry = entrySchema.parse({
    ...base,
    at: '2025-08-17',
    parts: {
      occasion: { v: 1, name: '提车', repeat: 'year', count: 'years', firstLabel: '提车日' },
    },
  })
  assert.deepEqual(getPublicEntry(entry).parts.occasion, {
    v: 1,
    name: '提车',
    repeat: 'year',
    count: 'years',
    firstLabel: '提车日',
  })
  assert.ok(entrySchema.safeParse({ ...base, parts: { occasion: { v: 1, name: '搬家' } } }).success)
  assert.ok(
    !entrySchema.safeParse({ ...base, parts: { occasion: { v: 1, name: '', repeat: 'month' } } })
      .success,
  )
})

test('a plan is a trip with its flag, and what was written into it stays planned both ways', () => {
  const plan = collectionSchema.parse({
    uid: getStableUlid(at, 'plan'),
    kind: 'trip',
    title: '东京',
    span: { start: '2026-12-24', end: '2026-12-29' },
    planned: true,
    createdAt: at,
    updatedAt: at,
  })
  assert.equal(collectionToTrip(plan, []).planned, true)
  assert.throws(() => collectionSchema.parse({ ...plan, planned: false }))

  const entry = entrySchema.parse({
    ...base,
    at: '2026-12-25T08:30:00+09:00',
    parts: { text: { v: 1, text: '早点出门。' } },
    planned: true,
  })
  assert.ok(isPlannedEntry(entry))
  assert.ok(!isPlannedEntry({ ...entry, planned: undefined }))
  const moment = momentRecordSchema.parse(entryToMoment(entry))
  assert.equal(moment.planned, true)
  assert.equal(momentToEntry(moment).planned, true)
})

test('a planned entry settled by its owner stays locked both ways', () => {
  const entry = entrySchema.parse({
    ...base,
    at: '2026-12-25T08:30:00+09:00',
    parts: { text: { v: 1, text: '早点出门。' } },
    planned: true,
    locked: true,
  })
  assert.throws(() => entrySchema.parse({ ...entry, locked: false }))
  const moment = momentRecordSchema.parse(entryToMoment(entry))
  assert.equal(moment.locked, true)
  assert.equal(momentToEntry(moment).locked, true)
})

test('a planned entry keeps how it is reached both ways', () => {
  const entry = entrySchema.parse({
    ...base,
    at: '2026-12-25T08:30:00+09:00',
    parts: { text: { v: 1, text: '坐电车过去。' } },
    planned: true,
    reachedBy: 'train',
  })
  assert.throws(() => entrySchema.parse({ ...entry, reachedBy: 'fly' }))
  const moment = momentRecordSchema.parse(entryToMoment(entry))
  assert.equal(moment.reachedBy, 'train')
  assert.equal(momentToEntry(moment).reachedBy, 'train')
})
