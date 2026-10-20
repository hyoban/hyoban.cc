import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { handle } from '../src/index.ts'
import { get, setUp, snapshot, token } from './setup.mjs'

// The reads: an import of the repository's data/, and what a client reads afterwards.

/** Every page of the change feed from a revision on. */
async function readFeed(env, { auth, since = 0, limit = 100 } = {}) {
  const moments = []
  const deleted = []
  let places
  let trips
  let path = `/v1/changes?since=${since}&limit=${limit}`
  let revision

  for (;;) {
    const { status, body } = await get(env, path, { auth })

    assert.equal(status, 200)
    revision ??= body.revision
    assert.equal(body.revision, revision, 'every page is of the first one’s revision')
    places ??= { places: body.places, deleted: body.deletedPlaces }
    trips ??= { trips: body.trips, deleted: body.deletedTrips }
    moments.push(...body.moments)
    deleted.push(...body.deleted)

    if (!body.next) {
      return { moments, deleted, revision, places, trips }
    }

    path = `/v1/changes?cursor=${body.next}&limit=${limit}`
  }
}

test('an import of data/ comes back out of the export exactly as written', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(snapshot)

  const { status, body } = await get(env, '/v1/export', { auth: token })

  assert.equal(status, 200)
  assert.equal(body.revision, 1)
  assert.deepEqual(body.manifest, snapshot.manifest)
  assert.deepEqual(body.places, snapshot.places)
  assert.deepEqual(
    body.trips,
    [...snapshot.trips].sort(
      (first, second) =>
        first.start.localeCompare(second.start) || first.id.localeCompare(second.id),
    ),
  )
  assert.deepEqual(
    body.moments,
    [...snapshot.moments].sort((first, second) => first.uid.localeCompare(second.uid)),
  )
})

test('the feed pages through every published Moment, and drafts only with a token', async () => {
  const { env, importSnapshot } = setUp()
  const [first, ...rest] = snapshot.moments
  const { published: _, ...draft } = first

  importSnapshot({ ...snapshot, moments: [draft, ...rest] })

  const publicFeed = await readFeed(env, { limit: 37 })
  const ownerFeed = await readFeed(env, { auth: token, limit: 500 })

  assert.equal(publicFeed.moments.length, rest.length)
  assert.ok(!publicFeed.moments.some((moment) => moment.uid === draft.uid))
  assert.equal(ownerFeed.moments.length, snapshot.moments.length)
  assert.equal((await get(env, `/v1/moments/${draft.uid}`)).status, 404)
  assert.deepEqual((await get(env, `/v1/moments/${draft.uid}`, { auth: token })).body, draft)
  assert.deepEqual((await get(env, `/v1/moments/${rest[0].uid}`)).body, rest[0])
})

test('importing again hands a client that synced only what changed', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(snapshot)

  const { revision } = await readFeed(env)
  const [removed, edited, ...rest] = snapshot.moments
  const changed = { ...edited, text: `${edited.text}\n\n补一句。` }

  importSnapshot({ ...snapshot, moments: [changed, ...rest] }, '2026-10-20T08:00:00Z')

  const since = await readFeed(env, { since: revision })

  assert.deepEqual(since.moments, [changed])
  assert.deepEqual(since.deleted, [removed.uid])
  assert.equal((await get(env, `/v1/moments/${removed.uid}`)).status, 404)

  importSnapshot({ ...snapshot, moments: [changed, ...rest] }, '2026-10-21T08:00:00Z')

  const unchanged = await readFeed(env, { since: since.revision })

  assert.deepEqual(unchanged, {
    moments: [],
    deleted: [],
    revision: since.revision + 1,
    places: { places: {}, deleted: [] },
    trips: { trips: [], deleted: [] },
  })
})

test('a feed from the start, page after page, leaves out what was removed', async () => {
  const { env, importSnapshot } = setUp()
  const [removed, ...rest] = snapshot.moments
  const [placeId] = Object.keys(snapshot.places)
  const { [placeId]: _, ...places } = snapshot.places
  const [goneTrip, ...trips] = snapshot.trips

  importSnapshot(snapshot)
  importSnapshot({ ...snapshot, places, trips, moments: rest }, '2026-10-20T08:00:00Z')

  const fresh = await readFeed(env, { limit: 7 })

  assert.equal(fresh.moments.length, rest.filter((moment) => moment.published).length)
  assert.deepEqual(fresh.deleted, [])
  assert.deepEqual(fresh.places.deleted, [])
  assert.deepEqual(fresh.trips.deleted, [])
  assert.ok(!(placeId in fresh.places.places))
  assert.ok(!fresh.trips.trips.some((trip) => trip.id === goneTrip.id))

  const since = await readFeed(env, { since: 1 })

  assert.deepEqual(since.deleted, [removed.uid], 'a feed after a revision still has them')
})

test('the feed hands out the places and trips whole from the start, then only what changed', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(snapshot)

  const all = await readFeed(env, { limit: 50 })

  assert.deepEqual(all.places.places, snapshot.places)
  assert.equal(all.trips.trips.length, snapshot.trips.length)

  const [placeId, place] = Object.entries(snapshot.places)[0]
  const [gone, moved, ...trips] = snapshot.trips
  const changedTrip = { ...moved, title: `${moved.title}（改）` }

  importSnapshot(
    {
      ...snapshot,
      places: { ...snapshot.places, [placeId]: { ...place, name: `${place.name}２` } },
      trips: [changedTrip, ...trips],
    },
    '2026-10-20T08:00:00Z',
  )

  const since = await readFeed(env, { since: all.revision })

  assert.deepEqual(since.places, {
    places: { [placeId]: { ...place, name: `${place.name}２` } },
    deleted: [],
  })
  assert.deepEqual(since.trips, { trips: [changedTrip], deleted: [gone.id] })
})

test('a feed read a page at a time stays at its first page’s revision', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(snapshot)

  const first = (await get(env, '/v1/changes?limit=10')).body
  const [edited, ...rest] = snapshot.moments
  const changed = { ...edited, text: `${edited.text}！` }

  importSnapshot({ ...snapshot, moments: [...rest, changed] }, '2026-10-20T08:00:00Z')

  const second = (await get(env, `/v1/changes?cursor=${first.next}&limit=10`)).body

  assert.equal(second.revision, first.revision)
  assert.deepEqual(second.places, {})
  assert.ok(!second.moments.some((moment) => moment.text === changed.text))
})

test('places and trips are read whole or one at a time', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(snapshot)

  const [placeId, place] = Object.entries(snapshot.places)[0]
  const trip = snapshot.trips[0]

  assert.deepEqual((await get(env, '/v1/places')).body.places, snapshot.places)
  assert.deepEqual((await get(env, `/v1/places/${placeId}`)).body, place)
  assert.equal((await get(env, '/v1/trips')).body.trips.length, snapshot.trips.length)
  assert.deepEqual((await get(env, `/v1/trips/${trip.id}`)).body, trip)
  assert.equal((await get(env, '/v1/places/nowhere-at-all')).status, 404)
})

test('tokens, methods and queries are checked', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(snapshot)

  assert.equal((await get(env, '/v1/export')).status, 401)
  assert.equal((await get(env, '/v1/changes', { auth: 'wrong' })).status, 401)
  assert.equal((await get(env, '/v1/moments')).status, 404)
  assert.equal((await get(env, '/v1/changes?limit=0')).status, 400)
  assert.equal((await get(env, '/v1/changes?cursor=nope')).status, 400)
  assert.equal((await get(env, '/v2/moments')).status, 404)
  assert.equal(
    (await handle(new Request('https://api.test/v1/moments', { method: 'POST' }), env)).status,
    401,
  )
  assert.equal(
    (
      await handle(
        new Request('https://api.test/v1/places', {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
        }),
        env,
      )
    ).status,
    405,
  )
  assert.deepEqual((await get(env, '/')).body, { name: 'hyoban.cc', revision: 1 })
})
