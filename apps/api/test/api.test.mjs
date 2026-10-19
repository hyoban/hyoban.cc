import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { handle } from '../src/index.ts'
import { get, setUp, snapshot, token } from './setup.mjs'

// The reads: an import of the repository's data/, and what a client reads afterwards.

/** Every page of the Moments feed from a revision on. */
async function readFeed(env, { auth, since = 0, limit = 100 } = {}) {
  const moments = []
  const deleted = []
  let path = `/v1/moments?since=${since}&limit=${limit}`
  let revision

  for (;;) {
    const { status, body } = await get(env, path, { auth })

    assert.equal(status, 200)
    revision ??= body.revision
    moments.push(...body.moments)
    deleted.push(...body.deleted)

    if (!body.next) {
      return { moments, deleted, revision }
    }

    path = `/v1/moments?cursor=${body.next}&limit=${limit}`
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

  assert.deepEqual(unchanged, { moments: [], deleted: [], revision: since.revision + 1 })
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

test('a Moment indexes its places in order', () => {
  const { importSnapshot, sqlite } = setUp()
  const moment = snapshot.moments.find((record) => record.places.length > 1)

  importSnapshot(snapshot)

  const rows = sqlite
    .prepare('SELECT place_id FROM moment_places WHERE moment_uid = ? ORDER BY position')
    .all(moment.uid)

  assert.deepEqual(
    rows.map((row) => row.place_id),
    moment.places,
  )
})

test('tokens, methods and queries are checked', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(snapshot)

  assert.equal((await get(env, '/v1/export')).status, 401)
  assert.equal((await get(env, '/v1/moments', { auth: 'wrong' })).status, 401)
  assert.equal((await get(env, '/v1/moments?limit=0')).status, 400)
  assert.equal((await get(env, '/v1/moments?cursor=nope')).status, 400)
  assert.equal((await get(env, '/v2/moments')).status, 404)
  assert.equal(
    (await handle(new Request('https://api.test/v1/moments', { method: 'POST' }), env)).status,
    401,
  )
  assert.equal(
    (
      await handle(
        new Request('https://api.test/v1/moments', {
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
