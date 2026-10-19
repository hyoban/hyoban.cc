import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { test } from 'vite-plus/test'

import { call, get, setUp, snapshot, token } from './setup.mjs'

// The writes, as the recording app makes them: places first, then the originals, the
// Moment as a draft, and publishing; over the repository's data/, so ids and orders meet
// the Moments already there.

const fixture = (file) =>
  JSON.parse(readFileSync(new URL(`../../../scripts/fixtures/bundle/${file}`, import.meta.url)))
const dockMoment = fixture('moments/2026/10/01M3VDBHC042D43KWZWE67YP1T.json')
const ferryMoment = fixture('moments/2026/10/01M3X2VJH0TR998A35TZVQ2JVX.json')
const photo = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4])
const motion = new Uint8Array([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70])
const owner = { auth: token }

function setUpWithData() {
  const setup = setUp()

  setup.importSnapshot()

  return setup
}

/** The order the next Moment on a day takes, so the test needn't know data/. */
function nextOrder(dateKey) {
  const orders = snapshot.moments
    .filter((moment) => moment.published && moment.occurredAt.startsWith(dateKey))
    .map((moment) => moment.published.order)

  return Math.max(0, ...orders) + 1
}

function upload(env, momentUid, mediaUid, part, body, contentType) {
  return call(env, 'PUT', `/v1/moments/${momentUid}/media/${mediaUid}/${part}`, {
    ...owner,
    body,
    headers: { 'Content-Type': contentType },
  })
}

function saveTestDock(env) {
  return call(env, 'PUT', '/v1/places/test-dock', {
    ...owner,
    body: { name: '测试码头', latitude: 1.234, longitude: 5.678 },
  })
}

/** The dock Moment with its places as the API knows them. */
function dockDraft() {
  return { ...dockMoment, places: ['nanjing', 'test-dock'] }
}

/** Saves the dock Moment, uploads its photo and publishes it. */
async function publishDock(env) {
  const draft = dockDraft()

  await saveTestDock(env)
  await call(env, 'PUT', `/v1/moments/${draft.uid}`, { ...owner, body: draft })
  await upload(env, draft.uid, draft.media[0].uid, 'image', photo, 'image/jpeg')

  return call(env, 'POST', `/v1/moments/${draft.uid}/publish`, owner)
}

test('a place near a known one, or with its name, comes back as that one', async () => {
  const { env } = setUpWithData()
  const nanjing = snapshot.places.nanjing
  const near = await call(env, 'PUT', '/v1/places/nanjing-2', {
    ...owner,
    body: fixture('places.json')['nanjing-2'],
  })
  const sameName = await call(env, 'PUT', '/v1/places/somewhere-else', {
    ...owner,
    body: { name: nanjing.name, latitude: 0, longitude: 0 },
  })
  const created = await saveTestDock(env)

  assert.deepEqual(near, { status: 200, body: { id: 'nanjing', place: nanjing } })
  assert.equal(sameName.body.id, 'nanjing')
  assert.equal(created.status, 201)
  assert.deepEqual((await get(env, '/v1/places/test-dock')).body, created.body.place)
  assert.equal((await get(env, '/v1/places/somewhere-else')).status, 404)
})

test('a draft is the owner’s until it publishes, with its media moved under its id', async () => {
  const { bucket, env } = setUpWithData()
  const draft = dockDraft()
  const [item] = draft.media

  await saveTestDock(env)

  const saved = await call(env, 'PUT', `/v1/moments/${draft.uid}`, { ...owner, body: draft })

  assert.equal(saved.status, 201)
  assert.equal((await get(env, `/v1/moments/${draft.uid}`)).status, 404)
  assert.deepEqual((await get(env, `/v1/moments/${draft.uid}`, owner)).body, saved.body)

  const early = await call(env, 'POST', `/v1/moments/${draft.uid}/publish`, owner)

  assert.equal(early.status, 422)
  assert.match(early.body.error, new RegExp(`${item.uid} \\(image\\)`))

  const uploaded = await upload(env, draft.uid, item.uid, 'image', photo, 'image/jpeg')

  assert.equal(uploaded.status, 201)
  assert.equal(uploaded.body.bytes, photo.byteLength)

  const published = await call(env, 'POST', `/v1/moments/${draft.uid}/publish`, owner)
  const order = nextOrder('2026-10-01')
  const id = `2026/10/01-${String(order).padStart(2, '0')}-nanjing`

  assert.equal(published.status, 200)
  assert.deepEqual(published.body.published, {
    id,
    slug: 'nanjing',
    order,
    at: published.body.published.at,
  })
  assert.deepEqual(published.body.media[0].published, {
    key: `moments/${id}/nanjing.jpg`,
    contentType: 'image/jpeg',
    bytes: photo.byteLength,
    etag: createHash('md5').update(photo).digest('hex'),
    width: item.width,
    height: item.height,
    variants: [],
  })
  assert.ok(bucket.objects.has(`moments/${id}/nanjing.jpg`))
  assert.ok(!bucket.objects.has(uploaded.body.key))
  assert.deepEqual((await get(env, `/v1/moments/${draft.uid}`)).body, published.body)
})

test('a published Moment keeps its id and blocks, and publishes new media as it saves', async () => {
  const { env } = setUpWithData()
  const { body: published } = await publishDock(env)
  const added = {
    uid: '01M3VDBHC1HW3W1Z58ARMSH299',
    type: 'live-photo',
    alt: '码头',
    width: 3024,
    height: 4032,
  }
  const edit = {
    ...published,
    text: '在码头等船，船晚点了。',
    published: { ...published.published, slug: 'changed' },
    media: [
      { ...published.media[0], published: { ...published.media[0].published, bytes: 1 } },
      added,
    ],
  }
  const missing = await call(env, 'PUT', `/v1/moments/${dockMoment.uid}`, { ...owner, body: edit })

  assert.equal(missing.status, 422)

  await upload(env, dockMoment.uid, added.uid, 'image', photo, 'image/jpeg')
  await upload(env, dockMoment.uid, added.uid, 'video', motion, 'video/quicktime')

  const saved = await call(env, 'PUT', `/v1/moments/${dockMoment.uid}`, { ...owner, body: edit })
  const id = published.published.id

  assert.equal(saved.status, 200, JSON.stringify(saved.body))
  assert.equal(saved.body.text, edit.text)
  assert.deepEqual(saved.body.published, published.published)
  assert.deepEqual(saved.body.media[0], published.media[0])
  assert.equal(saved.body.media[1].published.key, `moments/${id}/nanjing-2.jpg`)
  assert.equal(saved.body.media[1].published.video.key, `moments/${id}/nanjing-2.mov`)
  assert.equal(
    (await upload(env, dockMoment.uid, added.uid, 'image', photo, 'image/jpeg')).status,
    409,
  )
})

test('a Moment on a day with others takes the next order and its proposed slug', async () => {
  const { env } = setUpWithData()

  await call(env, 'PUT', `/v1/moments/${ferryMoment.uid}`, { ...owner, body: ferryMoment })

  const { body } = await call(env, 'POST', `/v1/moments/${ferryMoment.uid}/publish`, owner)
  const order = nextOrder('2026-10-02')

  assert.equal(body.published.id, `2026/10/02-${String(order).padStart(2, '0')}-ferry-morning`)
})

test('deleting hands the change feed a removal and calls the deploy hook', async () => {
  const { env } = setUpWithData()
  const calls = []
  const pending = []
  const context = { waitUntil: (promise) => pending.push(promise) }
  const realFetch = globalThis.fetch

  env.DEPLOY_HOOK_URL = 'https://deploy.test/hook'
  globalThis.fetch = async (url, init) => {
    calls.push([url, init?.method])
    return new Response(null, { status: 200 })
  }

  try {
    const { body: before } = await get(env, '/v1/moments?limit=1')
    const target = snapshot.moments.find((moment) => moment.published && !moment.hidden)
    const removed = await call(env, 'DELETE', `/v1/moments/${target.uid}`, { ...owner, context })

    await Promise.all(pending)

    assert.equal(removed.status, 204)
    assert.deepEqual(calls, [['https://deploy.test/hook', 'POST']])
    assert.equal((await get(env, `/v1/moments/${target.uid}`)).status, 404)

    const { body: after } = await get(env, `/v1/moments?since=${before.revision}`)

    assert.deepEqual(after.deleted, [target.uid])
    assert.equal((await call(env, 'DELETE', `/v1/moments/${target.uid}`, owner)).status, 404)

    const draft = await call(env, 'PUT', `/v1/moments/${ferryMoment.uid}`, {
      ...owner,
      body: ferryMoment,
      context,
    })

    await Promise.all(pending)

    assert.equal(draft.status, 201)
    assert.equal(calls.length, 1, 'saving a draft leaves the site alone')
  } finally {
    globalThis.fetch = realFetch
  }
})

test('trips name known places, and every write is stamped in the manifest', async () => {
  const { env } = setUpWithData()
  const trip = fixture('trips/2026-fixture-trip.json')
  const unknown = await call(env, 'PUT', `/v1/trips/${trip.id}`, { ...owner, body: trip })
  const renamed = {
    ...trip,
    waypoints: [{ ...trip.waypoints[0], place: 'nanjing' }],
    straightLegs: [{ ...trip.straightLegs[0], from: 'nanjing', to: 'test-dock' }],
  }

  await saveTestDock(env)

  const saved = await call(env, 'PUT', `/v1/trips/${trip.id}`, { ...owner, body: renamed })
  const again = await call(env, 'PUT', `/v1/trips/${trip.id}`, { ...owner, body: renamed })
  const { body: exported } = await get(env, '/v1/export', owner)

  assert.equal(unknown.status, 422)
  assert.match(unknown.body.error, /nanjing-2/)
  assert.equal(saved.status, 201)
  assert.equal(again.status, 200)
  assert.deepEqual((await get(env, `/v1/trips/${trip.id}`)).body, saved.body)
  assert.equal(exported.manifest.generator, 'hyoban.cc api')
  assert.notEqual(exported.manifest.updatedAt, snapshot.manifest.updatedAt)
})

test('writes need the token, a record that fits, and a type the API takes', async () => {
  const { env } = setUpWithData()
  const status = async (...args) => (await call(...args)).status

  assert.equal(await status(env, 'PUT', '/v1/places/test-dock', { body: {} }), 401)
  assert.equal(
    await status(env, 'PUT', `/v1/moments/${ferryMoment.uid}`, { ...owner, body: { text: 1 } }),
    422,
  )
  assert.equal(
    await status(env, 'PUT', `/v1/moments/${dockMoment.uid}`, { ...owner, body: ferryMoment }),
    422,
    'the uid in the path and the record disagree',
  )
  assert.equal(
    await status(env, 'PUT', `/v1/moments/${dockMoment.uid}`, { ...owner, body: dockMoment }),
    422,
    'its places are not known yet',
  )
  assert.equal(
    (await upload(env, dockMoment.uid, dockMoment.media[0].uid, 'image', photo, 'text/plain'))
      .status,
    415,
  )
  assert.equal(
    (await upload(env, dockMoment.uid, dockMoment.media[0].uid, 'video', photo, 'image/jpeg'))
      .status,
    415,
  )
})

test('a deploy hook that turns the call down is logged', async () => {
  const { env } = setUpWithData()
  const pending = []
  const logged = []
  const context = { waitUntil: (promise) => pending.push(promise) }
  const realFetch = globalThis.fetch
  const realError = console.error

  env.DEPLOY_HOOK_URL = 'https://deploy.test/gone'
  globalThis.fetch = async () => new Response('no such hook', { status: 404 })
  console.error = (...args) => logged.push(args)

  try {
    const target = snapshot.moments.find((moment) => moment.published)

    await call(env, 'DELETE', `/v1/moments/${target.uid}`, { ...owner, context })
    await Promise.all(pending)

    assert.deepEqual(logged, [['The deploy hook answered', 404, 'no such hook']])
  } finally {
    globalThis.fetch = realFetch
    console.error = realError
  }
})
