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

// The second shape, /v2: entries, collections and public tracks over the same rows, and what
// the public sees of them; and /v1 still serving the app installed before.

const owner = { auth: token }
const at = '2026-10-29T12:30:00+08:00'
const uid = (seed) => getStableUlid(at, seed)

function setUpWithData() {
  const setup = setUp()

  setup.importSnapshot()

  return setup
}

/** The repository's data/ moved over, as `data:migrate` writes it. */
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

test('importing the moved data/ serves the first shape as before', async () => {
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

test('the public sees no private entry, the owner all of them', async () => {
  const { env } = setUpWithData()
  const read = async (auth) => {
    const entries = []
    let path = '/v2/changes?since=0&limit=500'

    for (;;) {
      const { body } = await get(env, path, { auth })

      entries.push(...body.entries)

      if (!body.next) return entries

      path = `/v2/changes?cursor=${body.next}&limit=500`
    }
  }
  const shown = await read()
  const all = await read(token)
  const privateCount = snapshot.moments.filter(
    (moment) => moment.hidden || moment.topic === 'tech',
  ).length

  assert.equal(all.length, snapshot.moments.length + marks)
  assert.equal(shown.length, all.length - privateCount)
  assert.ok(shown.every((entry) => entry.visibility !== 'private' && entry.topic !== 'tech'))

  const tech = snapshot.moments.find((moment) => moment.topic === 'tech')

  assert.equal((await get(env, `/v2/entries/${tech.uid}`)).status, 404)
  assert.equal((await get(env, `/v2/entries/${tech.uid}`, owner)).body.uid, tech.uid)
})

test('a bill goes public by its amount, category and note alone, and a private one never comes', async () => {
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

  const publicMeal = (await get(env, `/v2/entries/${meal.uid}`)).body

  assert.deepEqual(publicMeal.parts.bill, {
    v: 1,
    visibility: 'public',
    amount: 18,
    currency: 'CNY',
    category: '餐饮',
    kind: 'expense',
  })
  assert.equal((await get(env, `/v2/entries/${meal.uid}`, owner)).body.parts.bill.account, 'acct')

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
  assert.equal((await get(env, '/v2/collections/test-trip')).status, 404)
  assert.equal((await get(env, '/v2/collections/test-trip', owner)).status, 200)

  await commit(env, { deletedCollections: ['test-trip'] })
  assert.equal((await get(env, '/v1/trips/test-trip')).status, 404)
})

test('a public span of footprints is kept and read by anyone, and goes when deleted', async () => {
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

test('making an entry private takes it from the public feed', async () => {
  const { env } = setUpWithData()
  const shown = snapshot.moments.find((moment) => !moment.hidden && moment.topic !== 'tech')
  const entry = (await get(env, `/v2/entries/${shown.uid}`, owner)).body
  const { body } = await commit(env, { entries: [{ ...entry, visibility: 'private' }] })
  const feed = (await get(env, `/v2/changes?since=${body.revision - 1}`)).body

  assert.deepEqual(feed.deleted, [shown.uid])
  assert.equal((await get(env, `/v1/moments/${shown.uid}`)).body.hidden, true)
})
