import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { fillRoutes, FETCHES_PER_RUN } from '../src/routes.ts'
import { call, get, setUp, snapshot, token } from './setup.mjs'

// The trips' routes: worked out from the trips and Moments in the database the way the
// routes script worked them out, fetched when missing, and served as the site draws them.

const owner = { auth: token }
const now = '2026-10-20T00:00:00Z'

function setUpWithData() {
  const setup = setUp()

  setup.importSnapshot()

  return setup
}

const refuse = async (leg) => {
  throw new Error(`Nothing should be fetched, but ${leg.from.name} → ${leg.to.name} was`)
}

test('the legs the trips need are the ones data/ has routes for, so none are fetched or dropped', async () => {
  const { env } = setUpWithData()
  const result = await fillRoutes({ db: env.DB, bucket: env.BUCKET, now }, refuse)

  assert.deepEqual(result, { fetched: 0, removed: 0, failed: 0, left: 0 })
})

test('a missing leg is fetched once, a train leg between the places’ stations', async () => {
  const { env, sqlite } = setUpWithData()
  const trainKey = Object.keys(snapshot.routes).find((key) => key.startsWith('train:'))
  const driveKey = Object.keys(snapshot.routes).find((key) => !key.startsWith('train:'))

  sqlite.prepare(`DELETE FROM routes WHERE key IN (?, ?)`).run(trainKey, driveKey)

  const asked = []
  const fetchRoute = async (leg) => {
    asked.push(leg)
    return {
      coordinates: [
        [leg.from.longitude, leg.from.latitude],
        [leg.to.longitude, leg.to.latitude],
      ],
      distance: 1000,
    }
  }
  const result = await fillRoutes({ db: env.DB, bucket: env.BUCKET, now }, fetchRoute)

  assert.equal(result.fetched, 2)
  assert.equal(result.removed, 0)

  const train = asked.find((leg) => leg.by === 'train')
  const places = Object.values(snapshot.places)

  // From a station when the place has one, never from a city's centre that has one.
  assert.ok(
    train.from.name.endsWith('站') ||
      !places.some((place) => place.station && place.name === train.from.name),
  )

  const { body } = await get(env, '/v1/export', owner)

  assert.ok(trainKey in body.routes && driveKey in body.routes)
  assert.deepEqual(await fillRoutes({ db: env.DB, bucket: env.BUCKET, now }, refuse), {
    fetched: 0,
    removed: 0,
    failed: 0,
    left: 0,
  })
})

test('a fill fetches at most a batch, and leaves the rest for the next', async () => {
  const { env, sqlite } = setUpWithData()

  sqlite.prepare(`DELETE FROM routes`).run()

  let calls = 0
  const result = await fillRoutes({ db: env.DB, bucket: env.BUCKET, now }, async () => {
    calls += 1
    return undefined
  })

  assert.equal(calls, FETCHES_PER_RUN)
  assert.equal(result.left, Object.keys(snapshot.routes).length - FETCHES_PER_RUN)
})

test('a route no trip needs any more is marked deleted', async () => {
  const fresh = setUpWithData()

  fresh.sqlite
    .prepare(
      `INSERT INTO routes (key, rev, record) VALUES ('1.00000,1.00000;2.00000,2.00000', 1, ?)`,
    )
    .run(
      JSON.stringify({
        coordinates: [
          [1, 1],
          [2, 2],
        ],
        distance: 1,
      }),
    )

  const removed = await fillRoutes({ db: fresh.env.DB, bucket: fresh.env.BUCKET, now }, refuse)

  assert.equal(removed.removed, 1)
  assert.ok(
    !('1.00000,1.00000;2.00000,2.00000' in (await get(fresh.env, '/v1/export', owner)).body.routes),
  )
})

test('GET /v1/routes gives every trip with Moments its stretches, drives solid and the rest dashed', async () => {
  const { env } = setUpWithData()
  const { status, body } = await get(env, '/v1/routes')

  assert.equal(status, 200)
  assert.ok(body.revision > 0)

  const trips = Object.entries(body.trips)

  assert.ok(trips.length > 0)

  for (const [, segments] of trips) {
    assert.ok(segments.length > 0)

    for (const segment of segments) {
      assert.equal(typeof segment.straight, 'boolean')
      assert.ok(segment.line.length >= 2)
    }
  }

  // The 2024 trip went by train and plane, so some of its stretches are dashed.
  assert.ok(body.trips['2024-code-and-travel'].some((segment) => segment.straight))
})

test('POST /v1/routes is the owner’s and needs the Mapbox token', async () => {
  const { env } = setUpWithData()

  assert.equal((await call(env, 'POST', '/v1/routes')).status, 401)
  assert.equal((await call(env, 'POST', '/v1/routes', owner)).status, 503)

  const { status, body } = await call(
    { ...env, MAPBOX_TOKEN: 'pk.test' },
    'POST',
    '/v1/routes',
    owner,
  )

  assert.equal(status, 200)
  assert.deepEqual(body, { fetched: 0, removed: 0, failed: 0, left: 0 })
})

test('a write the site shows brings the routes up to date before calling the deploy hook', async () => {
  const { env } = setUpWithData()
  const order = []
  const realFetch = globalThis.fetch

  globalThis.fetch = async (url) => {
    order.push(new Request(url).url.startsWith('https://deploy.test') ? 'deploy' : 'route')
    return new Response('{}', { status: 200 })
  }

  try {
    const pending = []
    const trip = snapshot.trips[0]

    await call(
      { ...env, MAPBOX_TOKEN: 'pk.test', DEPLOY_HOOK_URL: 'https://deploy.test/hook' },
      'PUT',
      `/v1/trips/${trip.id}`,
      {
        ...owner,
        body: { ...trip, title: `${trip.title}!` },
        context: { waitUntil: (promise) => pending.push(promise) },
      },
    )
    await Promise.all(pending)

    // Every leg is already there, so only the hook is called.
    assert.deepEqual(order, ['deploy'])
  } finally {
    globalThis.fetch = realFetch
  }
})
