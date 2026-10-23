import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { test } from 'vite-plus/test'

import { handle } from '../src/index.ts'
import { call, get, setUp, snapshot, token } from './setup.mjs'

// The writes, as the recording app commits what it kept on the phone: the originals
// uploaded first, then everything else as one POST /v1/changes; over the repository's
// data/, so ids and orders meet the Moments already there.

const fixture = (file) => JSON.parse(readFileSync(new URL(`fixtures/${file}`, import.meta.url)))
const dockMoment = fixture('dock-moment.json')
const ferryMoment = fixture('ferry-moment.json')
const photo = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4])
const motion = new Uint8Array([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70])
const owner = { auth: token }
const dock = { name: '测试码头', latitude: 1.234, longitude: 5.678 }
const made = 'p-01k7zq9x3m4n5p6q7r8s9t0v1w'

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

function commit(env, changes, options = {}) {
  return call(env, 'POST', '/v1/changes', { ...owner, body: changes, ...options })
}

/** The dock Moment at Nanjing, sent with a dock the app made. */
function dockDraft() {
  return { ...dockMoment, places: ['nanjing'] }
}

/** Uploads the dock Moment's photo and publishes it, with its dock. */
async function publishDock(env) {
  const draft = dockDraft()

  await upload(env, draft.uid, draft.media[0].uid, 'image', photo, 'image/jpeg')

  return commit(env, { places: { [made]: dock }, moments: [draft] })
}

test('a place near a known one, or with its name, is that one; one the app made is named by its pinyin', async () => {
  const { env } = setUpWithData()
  const nanjing = snapshot.places.nanjing
  const { status, body } = await commit(env, {
    places: {
      'nanjing-2': { name: '南京', latitude: 32.0607, longitude: 118.7972 },
      'somewhere-else': { name: nanjing.name, latitude: 0, longitude: 0 },
      [made]: dock,
    },
  })

  assert.equal(status, 200)
  assert.deepEqual(body.places, {
    'nanjing-2': 'nanjing',
    'somewhere-else': 'nanjing',
    [made]: 'ce-shi-ma-tou',
  })
  assert.deepEqual(
    (await get(env, '/v1/places/nanjing')).body,
    nanjing,
    'a known place keeps its point',
  )
  assert.equal((await get(env, '/v1/places/ce-shi-ma-tou')).body.name, '测试码头')
  assert.equal((await get(env, '/v1/places/somewhere-else')).status, 404)
})

test('a new place is kept with the division it is in, and without one when that can’t be looked up', async () => {
  const { env } = setUpWithData()
  const square = (x, y) => [
    [
      [x - 1, y - 1],
      [x + 1, y - 1],
      [x + 1, y + 1],
      [x - 1, y + 1],
      [x - 1, y - 1],
    ],
  ]
  const outlines = {
    100000: { adcode: 340000, name: '安徽省' },
    340000: { adcode: 340200, name: '芜湖市' },
    340200: { adcode: 340202, name: '镜湖区' },
  }
  let reachable = true
  const realFetch = globalThis.fetch

  globalThis.fetch = async (url) => {
    const code = /(\d+)_full\.json$/.exec(url)?.[1]
    const properties = outlines[code]

    if (!reachable) {
      return new Response('', { status: 503 })
    }

    return Response.json({
      features: properties
        ? [{ properties, geometry: { type: 'Polygon', coordinates: square(118.44, 31.35) } }]
        : [],
    })
  }

  try {
    const located = { ...env, DIVISIONS_URL: 'https://datav.test' }
    const lake = {
      name: '镜湖',
      latitude: 31.35,
      longitude: 118.43,
      kind: 'scenic',
      note: 'a lake',
    }
    const first = await commit(located, { places: { 'p-01k7zq9x3m4n5p6q7r8s9t0v1a': lake } })

    assert.equal(first.status, 200)
    assert.deepEqual((await get(env, '/v1/places/jing-hu')).body, {
      name: '镜湖',
      latitude: 31.35,
      longitude: 118.43,
      kind: 'scenic',
      division: { code: '340202', province: '安徽省', city: '芜湖市', district: '镜湖区' },
      note: 'a lake',
    })

    // DataV out of reach: kept as sent.
    reachable = false
    const plaza = { name: '中山广场', latitude: 31.3, longitude: 118.4 }
    const second = await commit(located, { places: { 'p-01k7zq9x3m4n5p6q7r8s9t0v1b': plaza } })

    assert.equal(second.status, 200)
    assert.deepEqual((await get(env, '/v1/places/zhong-shan-guang-chang')).body, plaza)
  } finally {
    globalThis.fetch = realFetch
  }
})

test('a Moment is at one place at most', async () => {
  const { env } = setUpWithData()
  const { status, body } = await commit(env, {
    moments: [{ ...ferryMoment, places: ['nanjing', 'yantai-port'] }],
  })

  assert.equal(status, 422)
  assert.match(body.error, /one place at most/)
})

test('a Moment of words alone is published under its first words', async () => {
  const { env } = setUpWithData()
  const { body } = await commit(env, { moments: [ferryMoment] })
  const order = nextOrder('2026-10-02')

  assert.equal(
    body.moments[0].published.id,
    `2026/10/02-${String(order).padStart(2, '0')}-hello-world-from-the-ferry`,
  )
})

test('a Moment publishes with its media moved under its id, once the originals are uploaded', async () => {
  const { bucket, env } = setUpWithData()
  const draft = dockDraft()
  const [item] = draft.media
  const early = await commit(env, { places: { [made]: dock }, moments: [draft] })

  assert.equal(early.status, 422)
  assert.match(early.body.error, new RegExp(`${item.uid} \\(image\\)`))
  assert.equal((await get(env, `/v1/moments/${draft.uid}`, owner)).status, 404)

  const uploaded = await upload(env, draft.uid, item.uid, 'image', photo, 'image/jpeg')

  assert.equal(uploaded.status, 201)
  assert.equal(uploaded.body.bytes, photo.byteLength)

  const { status, body } = await publishDock(env)
  const [published] = body.moments
  const order = nextOrder('2026-10-01')
  const id = `2026/10/01-${String(order).padStart(2, '0')}-nanjing`

  assert.equal(status, 200)
  assert.deepEqual(published.places, ['nanjing'])
  assert.deepEqual(published.published, { id, slug: 'nanjing', order, at: published.published.at })
  assert.deepEqual(published.media[0].published, {
    key: `moments/${id}/nanjing.jpg`,
    contentType: 'image/jpeg',
    bytes: photo.byteLength,
    etag: createHash('md5').update(photo).digest('hex'),
    width: item.width,
    height: item.height,
    variants: [],
    original: true,
  })
  assert.ok(bucket.objects.has(`moments/${id}/nanjing.jpg`))
  assert.ok(!bucket.objects.has(uploaded.body.key))
  assert.deepEqual((await get(env, `/v1/moments/${draft.uid}`)).body, published)
})

test('a published Moment keeps its id and blocks, and publishes new media as it changes', async () => {
  const { env } = setUpWithData()
  const {
    body: {
      moments: [published],
    },
  } = await publishDock(env)
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

  assert.equal(
    (await commit(env, { moments: [edit] })).status,
    422,
    'its new media are not uploaded',
  )

  await upload(env, dockMoment.uid, added.uid, 'image', photo, 'image/jpeg')
  await upload(env, dockMoment.uid, added.uid, 'video', motion, 'video/quicktime')

  const { status, body } = await commit(env, { moments: [edit] })
  const [saved] = body.moments
  const id = published.published.id

  assert.equal(status, 200, JSON.stringify(body))
  assert.equal(saved.text, edit.text)
  assert.deepEqual(saved.published, published.published)
  assert.deepEqual(saved.media[0], published.media[0])
  assert.equal(saved.media[1].published.key, `moments/${id}/nanjing-2.jpg`)
  assert.equal(saved.media[1].published.video.key, `moments/${id}/nanjing-2.mov`)
  assert.equal(
    (await upload(env, dockMoment.uid, added.uid, 'image', photo, 'image/jpeg')).status,
    409,
  )
})

test('the changes go as one write: places, Moments published, deletions, the site built once', async () => {
  const { bucket, env } = setUpWithData()
  const pending = []
  const calls = []
  const context = { waitUntil: (promise) => pending.push(promise) }
  const realFetch = globalThis.fetch
  const draft = dockDraft()
  const words = { ...ferryMoment, occurredAt: draft.occurredAt, places: [made] }
  const target = snapshot.moments.find((moment) => moment.published && !moment.hidden)
  const changes = {
    places: { [made]: dock },
    moments: [draft, words],
    deleted: [target.uid, '01M3VDBHC1HW3W1Z58ARMSH2ZZ'],
  }

  env.DEPLOY_HOOK_URL = 'https://deploy.test/hook'
  globalThis.fetch = async (url, init) => {
    calls.push([url, init?.method])
    return new Response(null, { status: 200 })
  }

  try {
    assert.equal((await commit(env, changes)).status, 422, 'the photo is not uploaded yet')
    assert.equal(
      (await get(env, '/v1/places/ce-shi-ma-tou')).status,
      404,
      'and nothing was written',
    )
    assert.equal((await get(env, `/v1/moments/${target.uid}`)).status, 200)

    await upload(env, draft.uid, draft.media[0].uid, 'image', photo, 'image/jpeg')

    const before = (await get(env, '/v1/changes?limit=1')).body.revision
    const applied = await commit(env, changes, { context })
    const order = nextOrder('2026-10-01')

    await Promise.all(pending)

    assert.equal(applied.status, 200, JSON.stringify(applied.body))
    assert.equal(applied.body.revision, before + 1, 'the revision the write took')
    assert.deepEqual(applied.body.places, { [made]: 'ce-shi-ma-tou' })
    assert.deepEqual(applied.body.deleted, [target.uid], 'one already gone is left out')
    assert.deepEqual(
      applied.body.moments.map((moment) => [moment.places, moment.published.id]),
      [
        [['nanjing'], `2026/10/01-${String(order).padStart(2, '0')}-nanjing`],
        [['ce-shi-ma-tou'], `2026/10/01-${String(order + 1).padStart(2, '0')}-ce-shi-ma-tou`],
      ],
      'two Moments on one day take the next two orders',
    )
    assert.ok(bucket.objects.has(applied.body.moments[0].media[0].published.key))
    assert.deepEqual(calls, [['https://deploy.test/hook', 'POST']], 'the site is built again once')
    assert.equal((await get(env, `/v1/moments/${target.uid}`)).status, 404)

    const { body: after } = await get(env, `/v1/changes?since=${before}`)

    assert.deepEqual(after.deleted, [target.uid])
    assert.deepEqual(
      after.moments
        .map((moment) => moment.uid)
        .sort((first, second) => first.localeCompare(second)),
      [draft.uid, words.uid].sort((first, second) => first.localeCompare(second)),
    )

    const { body: exported } = await get(env, '/v1/export', owner)

    assert.equal(exported.revision, before + 1, 'one write')
    assert.equal(exported.manifest.generator, 'hyoban.cc api')
    assert.notEqual(exported.manifest.updatedAt, snapshot.manifest.updatedAt)
  } finally {
    globalThis.fetch = realFetch
  }
})

test('changes that don’t fit are turned down whole', async () => {
  const { env } = setUpWithData()
  const status = async (body) => (await commit(env, body)).status
  const words = { ...ferryMoment, places: ['nanjing'] }

  assert.equal(await status({ moments: {} }), 422)
  assert.equal(await status({ moments: [{ text: 1 }] }), 422)
  assert.equal(await status({ places: { 'Not An Id': snapshot.places.nanjing } }), 422)
  assert.equal(
    await status({ moments: [{ ...words, places: ['nowhere'] }] }),
    422,
    'an unknown place',
  )
  assert.equal(await status({ moments: [words, words] }), 422, 'one Moment twice')
  assert.equal(await status({ moments: [words], deleted: [words.uid] }), 422, 'saved and deleted')
  assert.equal((await call(env, 'POST', '/v1/changes', { body: { moments: [words] } })).status, 401)
  assert.equal((await get(env, `/v1/moments/${words.uid}`, owner)).status, 404)
})

test('an original needs the token and a type the API takes', async () => {
  const { env } = setUpWithData()
  const [item] = dockMoment.media

  assert.equal(
    (
      await call(env, 'PUT', `/v1/moments/${dockMoment.uid}/media/${item.uid}/image`, {
        body: photo,
        headers: { 'Content-Type': 'image/jpeg' },
      })
    ).status,
    401,
  )
  assert.equal(
    (await upload(env, dockMoment.uid, item.uid, 'image', photo, 'text/plain')).status,
    415,
  )
  assert.equal(
    (await upload(env, dockMoment.uid, item.uid, 'video', photo, 'image/jpeg')).status,
    415,
  )
})

test('the records are read, not written, one by one', async () => {
  const { env } = setUpWithData()
  const [place] = Object.keys(snapshot.places)

  assert.equal(
    (await call(env, 'PUT', `/v1/moments/${ferryMoment.uid}`, { ...owner, body: ferryMoment }))
      .status,
    405,
  )
  assert.equal(
    (await call(env, 'DELETE', `/v1/moments/${snapshot.moments[0].uid}`, owner)).status,
    405,
  )
  assert.equal(
    (await call(env, 'POST', `/v1/moments/${ferryMoment.uid}/publish`, owner)).status,
    404,
  )
  assert.equal(
    (await call(env, 'PUT', `/v1/places/${place}`, { ...owner, body: dock })).status,
    405,
  )
  assert.equal(
    (
      await call(env, 'PUT', `/v1/trips/${snapshot.trips[0].id}`, {
        ...owner,
        body: snapshot.trips[0],
      })
    ).status,
    405,
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

    await commit(env, { deleted: [target.uid] }, { context })
    await Promise.all(pending)

    assert.deepEqual(logged, [['The deploy hook answered', 404, 'no such hook']])
  } finally {
    globalThis.fetch = realFetch
    console.error = realError
  }
})

test('a Moment sent as it is stored writes nothing and builds nothing', async () => {
  const { env } = setUpWithData()
  const pending = []
  const context = { waitUntil: (promise) => pending.push(promise) }
  const {
    body: {
      moments: [published],
    },
  } = await publishDock(env)
  const before = (await get(env, '/v1/changes?limit=1')).body.revision
  const again = await commit(env, { moments: [published] }, { context })

  assert.equal(again.status, 200)
  assert.deepEqual(again.body.moments, [published])
  assert.ok(!('revision' in again.body), 'and takes no revision')
  assert.equal((await get(env, '/v1/changes?limit=1')).body.revision, before)
  assert.equal(pending.length, 0)
})

test('HEAD on an original says whether it is uploaded, and its MD5', async () => {
  const { env } = setUpWithData()
  const [item] = dockMoment.media
  const path = `/v1/moments/${dockMoment.uid}/media/${item.uid}/image`

  assert.equal((await call(env, 'HEAD', path)).status, 401)

  const missing = await handle(
    new Request(`https://api.test${path}`, {
      method: 'HEAD',
      headers: { Authorization: `Bearer ${token}` },
    }),
    env,
  )

  assert.equal(missing.status, 404)

  await upload(env, dockMoment.uid, item.uid, 'image', photo, 'image/jpeg')

  const found = await handle(
    new Request(`https://api.test${path}`, {
      method: 'HEAD',
      headers: { Authorization: `Bearer ${token}` },
    }),
    env,
  )

  assert.equal(found.status, 200)
  assert.equal(found.headers.get('ETag'), `"${createHash('md5').update(photo).digest('hex')}"`)
})

test('a write that fails after its media are copied removes the copies', async () => {
  const { bucket, env } = setUpWithData()
  const draft = dockDraft()

  await upload(env, draft.uid, draft.media[0].uid, 'image', photo, 'image/jpeg')

  const realBatch = env.DB.batch

  env.DB.batch = async (statements) => {
    if (
      statements.some((statement) => statement.query.startsWith('UPDATE meta SET value = CAST'))
    ) {
      throw new Error('D1 is down')
    }

    return realBatch(statements)
  }

  const realError = console.error

  console.error = () => {}

  try {
    assert.equal((await commit(env, { places: { [made]: dock }, moments: [draft] })).status, 500)
  } finally {
    console.error = realError
    env.DB.batch = realBatch
  }

  assert.deepEqual(
    [...bucket.objects.keys()],
    [`uploads/${draft.uid}/${draft.media[0].uid}/image`],
    'the upload stays for the next try, and the copy is gone',
  )
})
