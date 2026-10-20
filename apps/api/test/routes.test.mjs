import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { handle } from '../src/index.ts'
import { getLegKey } from '@hyoban/trip-map'

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

/** What `meta` keeps of `GET /v1/routes`, as it answers it. */
function readKept(sqlite) {
  const value = (key) => sqlite.prepare(`SELECT value FROM meta WHERE key = ?`).get(key)?.value

  return {
    revision: Number(value('trip_routes_revision')),
    trips: JSON.parse(value('trip_routes')),
  }
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

test('a leg with no route is remembered and not asked for again, and one that failed is', async () => {
  const { env, sqlite } = setUpWithData()
  const [noRoad, failing] = Object.keys(snapshot.routes).filter((key) => !key.startsWith('train:'))

  sqlite.prepare(`DELETE FROM routes WHERE key IN (?, ?)`).run(noRoad, failing)

  const asked = []
  const fetchRoute = async (leg) => {
    const key = getLegKey(leg.from, leg.to)

    asked.push(key)

    if (key === failing) {
      throw new Error('Directions is down')
    }

    return undefined
  }
  const realError = console.error
  const realWarn = console.warn

  console.error = () => {}
  console.warn = () => {}

  try {
    const first = await fillRoutes({ db: env.DB, bucket: env.BUCKET, now }, fetchRoute)

    assert.deepEqual(first, { fetched: 0, removed: 0, failed: 1, left: 0 })
    assert.deepEqual(new Set(asked), new Set([noRoad, failing]))

    asked.length = 0

    const second = await fillRoutes({ db: env.DB, bucket: env.BUCKET, now }, fetchRoute)

    assert.equal(second.failed, 1)
    assert.deepEqual(asked, [failing], 'only the one that failed is asked for again')
  } finally {
    console.error = realError
    console.warn = realWarn
  }
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

test('GET /v1/routes is worked out once per revision, and again once the revision moves on', async () => {
  const { env, sqlite } = setUpWithData()
  const kept = () => readKept(sqlite)
  const first = (await get(env, '/v1/routes')).body

  assert.deepEqual(kept(), first)

  // A trip with nothing in it any more, as a write past the Worker (data:push) leaves it.
  sqlite.exec(`UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'`)
  sqlite.exec(`UPDATE trips SET deleted_at = '${now}' WHERE id = '2024-code-and-travel'`)

  const second = (await get(env, '/v1/routes')).body

  assert.equal(second.revision, first.revision + 1)
  assert.ok(!('2024-code-and-travel' in second.trips))
  assert.deepEqual(kept(), second)
})

test('GET /v1/routes has an ETag of its routes, which a new revision with the same routes keeps', async () => {
  const { env, sqlite } = setUpWithData()
  const first = await handle(new Request('https://api.test/v1/routes'), env)
  const etag = first.headers.get('ETag')

  assert.equal(first.status, 200)
  assert.match(etag, /^"[0-9a-f]{32}"$/)

  sqlite.exec(`UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'`)

  const unchanged = await handle(
    new Request('https://api.test/v1/routes', { headers: { 'If-None-Match': etag } }),
    env,
  )

  assert.equal(unchanged.status, 304)
  assert.equal(unchanged.headers.get('ETag'), etag)
  assert.equal(readKept(sqlite).revision, 2, 'worked out again for the new revision')

  sqlite.exec(`UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'`)
  sqlite.exec(`UPDATE trips SET deleted_at = '${now}' WHERE id = '2024-code-and-travel'`)

  const changed = await handle(
    new Request('https://api.test/v1/routes', { headers: { 'If-None-Match': etag } }),
    env,
  )

  assert.equal(changed.status, 200)
  assert.notEqual(changed.headers.get('ETag'), etag)
})

test('a fill keeps what GET /v1/routes answers, of the revision its own write took', async () => {
  const { env, sqlite } = setUpWithData()
  const driveKey = Object.keys(snapshot.routes).find((key) => !key.startsWith('train:'))

  sqlite.prepare(`DELETE FROM routes WHERE key = ?`).run(driveKey)

  const route = snapshot.routes[driveKey]
  const result = await fillRoutes({ db: env.DB, bucket: env.BUCKET, now }, async () => route)
  const revision = Number(
    sqlite.prepare(`SELECT value FROM meta WHERE key = 'revision'`).get().value,
  )
  const kept = readKept(sqlite)

  assert.equal(result.fetched, 1)
  assert.equal(kept.revision, revision)
  assert.deepEqual((await get(env, '/v1/routes')).body, kept)
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

test('a write the site shows brings the routes up to date before calling the deploy hook, when the trips see it', async () => {
  const { env, sqlite } = setUpWithData()
  const order = []
  const realFetch = globalThis.fetch

  globalThis.fetch = async (url) => {
    order.push(new Request(url).url.startsWith('https://deploy.test') ? 'deploy' : 'route')
    return new Response('{}', { status: 200 })
  }

  try {
    const before = (await get(env, '/v1/routes')).body
    const moment = snapshot.moments.find(
      (record) => record.published && !record.hidden && record.places.length > 0,
    )
    const write = async (changed) => {
      const pending = []

      order.length = 0
      await call(
        { ...env, MAPBOX_TOKEN: 'pk.test', DEPLOY_HOOK_URL: 'https://deploy.test/hook' },
        'POST',
        '/v1/changes',
        {
          ...owner,
          body: { moments: [changed] },
          context: { waitUntil: (promise) => pending.push(promise) },
        },
      )
      await Promise.all(pending)
    }

    // Every leg gone, which a fill would fetch.
    sqlite.prepare(`DELETE FROM routes`).run()

    // Words alone: the trips don't see them, so the routes aren't touched and their
    // answer is carried on to the write's revision.
    await write({ ...moment, text: `${moment.text}!` })

    assert.deepEqual(order, ['deploy'])
    assert.deepEqual(readKept(sqlite), { revision: before.revision + 1, trips: before.trips })

    // Hidden, it leaves the trips, so the routes are brought up to date first.
    await write({ ...moment, text: `${moment.text}!`, hidden: true })

    assert.ok(order.includes('route'))
    assert.equal(order.at(-1), 'deploy')
  } finally {
    globalThis.fetch = realFetch
  }
})
