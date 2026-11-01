import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import {
  collectionToTrip,
  entryToMoment,
  getStableUlid,
  momentToEntry,
  tripToCollection,
} from '@hyoban/data-spec/entry'
import { call, get, setUp, snapshot, token } from './setup.mjs'

// The second shape, /v2: entries, collections and public tracks over the same rows, all of
// them the owner's to read; and /v1 still serving the app installed before.

const owner = { auth: token }
const at = '2026-10-29T12:30:00+08:00'
const uid = (seed) => getStableUlid(at, seed)

function setUpWithData() {
  const setup = setUp()

  setup.importSnapshot()

  return setup
}

/** The fixture moved over, as `data:migrate` writes it. */
function movedSnapshot() {
  const entries = snapshot.moments.map((moment) => momentToEntry(moment))
  const collections = snapshot.trips.map((trip) => {
    const { collection, marks } = tripToCollection(trip, snapshot.moments)

    entries.push(...marks)

    return collection
  })

  return {
    manifest: { ...snapshot.manifest, schemaVersion: 3 },
    places: snapshot.places,
    entries,
    collections,
    routes: snapshot.routes,
  }
}

function commit(env, changes) {
  return call(env, 'POST', '/v2/changes', { ...owner, body: changes })
}

const marks = snapshot.trips.reduce((sum, trip) => sum + trip.waypoints.length, 0)

test('the first layout imported reads back in the second shape, marks and all', async () => {
  const { env } = setUpWithData()
  const { body } = await get(env, '/v2/export', owner)

  assert.equal(body.entries.length, snapshot.moments.length + marks)
  assert.equal(body.collections.length, snapshot.trips.length)
  assert.ok(body.collections.every((collection) => collection.slug && collection.kind === 'trip'))
  assert.equal((await get(env, '/v1/export', owner)).body.moments.length, snapshot.moments.length)
})

test('importing the moved layout serves the first shape as before', async () => {
  const first = setUpWithData()
  const second = setUp()

  second.importSnapshot(movedSnapshot())

  const before = (await get(first.env, '/v1/export', owner)).body
  const after = (await get(second.env, '/v1/export', owner)).body

  assert.deepEqual(after.moments, before.moments)
  assert.deepEqual(after.trips, before.trips)
  assert.deepEqual(
    (await get(second.env, '/v2/export', owner)).body.entries.length,
    snapshot.moments.length + marks,
  )
})

test('the feed hands out every entry, private ones too', async () => {
  const { env } = setUpWithData()
  const entries = []
  let path = '/v2/changes?since=0&limit=5'

  for (;;) {
    const { body } = await get(env, path, owner)

    entries.push(...body.entries)

    if (!body.next) break

    path = `/v2/changes?cursor=${body.next}&limit=5`
  }

  const hidden = snapshot.moments.find((moment) => moment.hidden)

  assert.equal(entries.length, snapshot.moments.length + marks)
  assert.equal(entries.find((entry) => entry.uid === hidden.uid).visibility, 'private')
  assert.equal((await get(env, `/v2/entries/${hidden.uid}`, owner)).body.uid, hidden.uid)
  assert.equal((await get(env, `/v2/entries/${hidden.uid}`, { auth: null })).status, 401)
})

test('a bill comes back whole, and a private one alone never comes', async () => {
  const { env } = setUpWithData()
  const meal = {
    uid: uid('meal'),
    at,
    place: 'nanjing',
    parts: {
      text: { v: 1, text: '吃了一碗面' },
      food: { v: 1, dishes: ['面'], cost: 18 },
      bill: {
        v: 1,
        visibility: 'public',
        amount: 18,
        currency: 'CNY',
        category: '餐饮',
        account: 'acct',
        book: 'book',
      },
    },
    createdAt: at,
    updatedAt: at,
  }
  const alone = {
    uid: uid('alone'),
    at,
    parts: { bill: { v: 1, amount: 9.5, currency: 'CNY', category: '交通' } },
    createdAt: at,
    updatedAt: at,
  }
  const { status, body } = await commit(env, { entries: [meal, alone] })

  assert.equal(status, 200)
  assert.ok(body.revision)
  assert.ok(body.entries[0].published)
  // The private bill alone stayed on the phone: the entry came with no parts.
  assert.deepEqual(body.entries[1].parts, {})

  assert.deepEqual((await get(env, `/v2/entries/${meal.uid}`, owner)).body.parts.bill, {
    v: 1,
    visibility: 'public',
    amount: 18,
    currency: 'CNY',
    category: '餐饮',
    account: 'acct',
    book: 'book',
    kind: 'expense',
  })

  // The first shape sees the meal as a Moment, its bill as the meal's cost.
  const moment = (await get(env, `/v1/moments/${meal.uid}`)).body

  assert.equal(moment.text, '吃了一碗面')
  assert.deepEqual(moment.food.bill, { amount: 18 })
  assert.equal((await get(env, `/v1/moments/${alone.uid}`, owner)).status, 404)
})

test('a Moment written in the first shape keeps the parts it knows nothing of', async () => {
  const { env } = setUpWithData()
  const entry = {
    uid: uid('keep'),
    at,
    parts: {
      text: { v: 1, text: '充电' },
      charge: { v: 1, type: 'DC', energyWh: 30000, cost: 20 },
    },
    createdAt: at,
    updatedAt: at,
  }

  await commit(env, { entries: [entry] })

  const moment = (await get(env, `/v1/moments/${entry.uid}`, owner)).body
  const { status } = await call(env, 'POST', '/v1/changes', {
    ...owner,
    body: { moments: [{ ...moment, text: '充了电' }] },
  })

  assert.equal(status, 200)

  const after = (await get(env, `/v2/entries/${entry.uid}`, owner)).body

  assert.equal(after.parts.text.text, '充了电')
  assert.equal(after.parts.charge.energyWh, 30000)
  assert.equal(entryToMoment(after).text, '充了电')
})

test('a collection and its marks write the trip the first shape reads', async () => {
  const { env } = setUpWithData()
  const collection = {
    uid: uid('trip'),
    slug: 'test-trip',
    kind: 'trip',
    title: '测试旅程',
    span: { start: '2026-10-29', end: '2026-10-30' },
    createdAt: at,
    updatedAt: at,
  }
  const mark = {
    uid: uid('mark'),
    at,
    place: 'nanjing',
    parts: {},
    order: 1,
    createdAt: at,
    updatedAt: at,
  }
  const { status } = await commit(env, { collections: [collection], entries: [mark] })

  assert.equal(status, 200)

  const trip = (await get(env, '/v1/trips/test-trip')).body

  assert.deepEqual(trip.waypoints, [{ at, place: 'nanjing' }])
  assert.equal((await get(env, '/v2/collections/test-trip')).body.uid, collection.uid)
  assert.deepEqual(
    trip,
    collectionToTrip({ include: [], exclude: [], covers: [], ...collection }, [mark]),
  )

  await commit(env, { collections: [{ ...collection, visibility: 'private' }] })
  assert.equal((await get(env, '/v2/collections/test-trip', owner)).body.visibility, 'private')

  await commit(env, { deletedCollections: ['test-trip'] })
  assert.equal((await get(env, '/v1/trips/test-trip')).status, 404)
})

test('a public span of footprints is kept and read, and goes when deleted', async () => {
  const { env } = setUpWithData()
  const track = {
    uid: uid('track'),
    start: '2026-10-29T08:00:00+08:00',
    end: '2026-10-29T18:00:00+08:00',
    simplify: 30,
    segments: [
      {
        mode: 'drive',
        coordinates: [
          [118.4, 31.3, 0],
          [118.5, 31.4, 60],
        ],
      },
    ],
    distance: 14000,
  }

  assert.equal((await commit(env, { tracks: [track] })).status, 200)
  assert.deepEqual((await get(env, '/v2/tracks')).body.tracks, [track])

  const feed = (await get(env, '/v2/changes?since=1')).body

  assert.deepEqual(feed.tracks, [track])

  await commit(env, { deletedTracks: [track.uid] })
  assert.deepEqual((await get(env, '/v2/tracks')).body.tracks, [])
  assert.deepEqual((await get(env, '/v2/changes?since=2')).body.deletedTracks, [track.uid])
})

test('an entry made private comes back on the feed as private, its Moment hidden', async () => {
  const { env } = setUpWithData()
  const shown = snapshot.moments.find((moment) => !moment.hidden)
  const entry = (await get(env, `/v2/entries/${shown.uid}`, owner)).body
  const { body } = await commit(env, { entries: [{ ...entry, visibility: 'private' }] })
  const feed = (await get(env, `/v2/changes?since=${body.revision - 1}`, owner)).body

  assert.deepEqual(feed.deleted, [])
  assert.deepEqual(
    feed.entries.map((changed) => [changed.uid, changed.visibility]),
    [[shown.uid, 'private']],
  )
  assert.equal((await get(env, `/v1/moments/${shown.uid}`, owner)).body.hidden, true)
})

test('a subscription comes back with what pays for it', async () => {
  const { env } = setUpWithData()
  const subscription = {
    uid: uid('subscription'),
    at: '2025-11-14',
    parts: {
      subscription: {
        v: 1,
        name: 'Folo Plus',
        price: 79.99,
        currency: 'USD',
        period: 'year',
        renews: '2026-11-14',
        paidWith: 'A',
      },
    },
    createdAt: at,
    updatedAt: at,
  }

  assert.equal((await commit(env, { entries: [subscription] })).status, 200)

  const read = (await get(env, `/v2/entries/${subscription.uid}`, owner)).body.parts.subscription

  assert.equal(read.name, 'Folo Plus')
  assert.equal(read.paidWith, 'A')
})

test('an imported charge is changed only by an import', async () => {
  const { env, importSnapshot } = setUpWithData()
  const charge = {
    uid: uid('charge'),
    at,
    parts: {
      charge: {
        v: 1,
        type: 'AC',
        energyWh: 7800,
        vehicle: 'zeekr-007gt',
        source: { captureId: 'a'.repeat(64), pointer: '/charges/0', method: 'screen' },
      },
    },
    published: { at },
    createdAt: at,
    updatedAt: at,
  }
  const sent = await commit(env, { entries: [charge] })

  assert.equal(sent.status, 422)
  assert.match(sent.body.error, /imported charge/)

  importSnapshot({ ...movedSnapshot(), entries: [...movedSnapshot().entries, charge] })

  const { source: _source, ...edited } = charge.parts.charge
  const changed = await commit(env, { entries: [{ ...charge, parts: { charge: edited } }] })

  assert.equal(changed.status, 422)
  assert.equal((await commit(env, { deleted: [charge.uid] })).status, 422)
})

test("the car's statistics come back in the export and on the feed's first page", async () => {
  const { env, importSnapshot } = setUpWithData()
  const capture = {
    id: 'b'.repeat(64),
    deviceId: 'zeekr-007gt',
    capturedAt: '2026-10-24T03:10:00+08:00',
    timezone: 'Asia/Shanghai',
    raw: { file: 'sources/x.json', sha256: 'b'.repeat(64), redacted: true },
  }
  const source = { captureId: capture.id, pointer: '/report', method: 'screen' }
  const month = {
    deviceId: 'zeekr-007gt',
    month: '2026-09',
    capturedAt: capture.capturedAt,
    report: { distanceMeters: 1895000 },
    sources: [source],
  }

  importSnapshot({ ...movedSnapshot(), vehicleMonths: [month], vehicleCaptures: [capture] })

  const exported = (await get(env, '/v2/export', owner)).body

  assert.deepEqual(exported.vehicleMonths, [month])
  assert.deepEqual(exported.vehicleCaptures, [capture])
  assert.deepEqual(exported.vehicleAssisted, [])

  const feed = (await get(env, '/v2/changes?since=0')).body

  assert.deepEqual(feed.vehicleMonths, [month])
  assert.deepEqual(feed.deletedVehicleMonths, [])
})
