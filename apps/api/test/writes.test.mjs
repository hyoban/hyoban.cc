import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { test } from 'vite-plus/test'

import { handle } from '../src/index.ts'
import {
  call,
  get,
  getMoment,
  momentsOf,
  publishMoments,
  readChanges,
  setUp,
  snapshot,
  token,
} from './setup.mjs'

// The writes, as the recording app commits what it kept on the phone: the originals
// uploaded first, then everything else as one POST /v2/changes; over the made-up data/,
// so they meet the Moments already there.

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

/** Where a media file of a Moment goes: under its uid, named by the item's. */
const keyOf = (momentUid, mediaUid, extension, part) =>
  `moments/${momentUid.toLowerCase()}/${mediaUid.toLowerCase()}${part ? `-${part}` : ''}.${extension}`

function upload(env, momentUid, mediaUid, part, body, contentType) {
  return call(env, 'PUT', `/v2/entries/${momentUid}/media/${mediaUid}/${part}`, {
    ...owner,
    body,
    headers: { 'Content-Type': contentType },
  })
}

function commit(env, changes, options = {}) {
  return publishMoments(env, changes, options)
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

test('a new place stays apart from a known one however near or alike; one the app made is named by its pinyin', async () => {
  const { env } = setUpWithData()
  const nanjing = snapshot.places.nanjing
  const { status, body } = await commit(env, {
    places: {
      'nanjing-2': { name: '南京', latitude: nanjing.latitude, longitude: nanjing.longitude },
      'somewhere-else': { name: nanjing.name, latitude: 0, longitude: 0 },
      [made]: dock,
    },
  })

  assert.equal(status, 200)
  assert.deepEqual(body.places, {
    'nanjing-2': 'nanjing-2',
    'somewhere-else': 'somewhere-else',
    [made]: 'ce-shi-ma-tou',
  })
  assert.deepEqual(
    (await get(env, '/v2/places/nanjing')).body,
    nanjing,
    'the known place is as it was',
  )
  assert.equal((await get(env, '/v2/places/ce-shi-ma-tou')).body.name, '测试码头')
  assert.equal((await get(env, '/v2/places/somewhere-else')).body.name, nanjing.name)
})

test('a place from a Swarm venue is that venue’s alone, however near or alike another is', async () => {
  const { env } = setUpWithData()
  const nanjing = snapshot.places.nanjing
  const venue = {
    name: nanjing.name,
    latitude: nanjing.latitude,
    longitude: nanjing.longitude,
    source: { app: 'swarm', venue: 'v1' },
  }
  const first = await commit(env, { places: { 'p-01m2m39jfrtecgk13xngmx8864': venue } })

  assert.equal(first.status, 200)
  const id = first.body.places['p-01m2m39jfrtecgk13xngmx8864']
  assert.notEqual(id, 'nanjing', 'not the known place it stands on')
  assert.deepEqual((await get(env, `/v2/places/${id}`)).body.source, venue.source)

  const again = await commit(env, {
    places: {
      'p-01m2m3hw3ry3kwpp7kxsb6gsng': { ...venue, name: '别的名字' },
      'p-01m2m4tpr8ev0n3n7cr60w8cjg': { ...venue, source: { app: 'swarm', venue: 'v2' } },
    },
  })

  assert.equal(
    again.body.places['p-01m2m3hw3ry3kwpp7kxsb6gsng'],
    id,
    'the same venue again is its place',
  )
  assert.notEqual(
    again.body.places['p-01m2m4tpr8ev0n3n7cr60w8cjg'],
    id,
    'another venue is another place',
  )
})

test('a known place sent under its own id takes its name, point and kind, and keeps the rest', async () => {
  const { env } = setUpWithData()
  const nanjing = snapshot.places.nanjing
  const kind = nanjing.kind === 'scenic' ? 'food' : 'scenic'
  const renamed = await commit(env, { places: { nanjing: { ...nanjing, name: '别的名字', kind } } })

  assert.equal(renamed.status, 200)
  assert.ok(renamed.body.revision, 'and takes a revision')
  assert.deepEqual((await get(env, '/v2/places/nanjing')).body, {
    ...nanjing,
    name: '别的名字',
    kind,
  })

  const sent = { name: '别的名字', latitude: 0, longitude: nanjing.longitude, kind }
  const moved = await commit(env, { places: { nanjing: sent } })
  const kept = { ...nanjing, ...sent }

  delete kept.division

  assert.equal(moved.status, 200)
  assert.deepEqual(
    (await get(env, '/v2/places/nanjing')).body,
    kept,
    'moved, it loses the division it was in, and keeps its note and the rest',
  )

  const again = await commit(env, { places: { nanjing: sent } })

  assert.ok(!('revision' in again.body), 'sent as it is, it writes nothing')
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
    assert.deepEqual((await get(env, '/v2/places/jing-hu')).body, {
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
    assert.deepEqual((await get(env, '/v2/places/zhong-shan-guang-chang')).body, plaza)
  } finally {
    globalThis.fetch = realFetch
  }
})

test('a Moment of words alone is published with the time, and named by its uid alone', async () => {
  const { env } = setUpWithData()
  const { body } = await commit(env, { moments: [ferryMoment] })

  assert.deepEqual(Object.keys(body.moments[0].published), ['at'])
  assert.equal(body.moments[0].uid, ferryMoment.uid)
})

test('a Moment publishes with its media moved under its uid, once the originals are uploaded', async () => {
  const { bucket, env } = setUpWithData()
  const draft = dockDraft()
  const [item] = draft.media
  const early = await commit(env, { places: { [made]: dock }, moments: [draft] })

  assert.equal(early.status, 422)
  assert.match(early.body.error, new RegExp(`${item.uid} \\(image\\)`))
  assert.equal((await getMoment(env, draft.uid)).status, 404)

  const uploaded = await upload(env, draft.uid, item.uid, 'image', photo, 'image/jpeg')

  assert.equal(uploaded.status, 201)
  assert.equal(uploaded.body.bytes, photo.byteLength)

  const { status, body } = await publishDock(env)
  const [published] = body.moments
  const key = keyOf(draft.uid, item.uid, 'jpg')

  assert.equal(status, 200)
  assert.deepEqual(published.places, ['nanjing'])
  assert.deepEqual(published.published, { at: published.published.at })
  assert.deepEqual(published.media[0].published, {
    key,
    contentType: 'image/jpeg',
    bytes: photo.byteLength,
    etag: createHash('md5').update(photo).digest('hex'),
    width: item.width,
    height: item.height,
    variants: [],
    original: true,
  })
  assert.ok(bucket.objects.has(key))
  assert.ok(!bucket.objects.has(uploaded.body.key))
  assert.deepEqual((await getMoment(env, draft.uid)).body, published)
})

test('a published Moment keeps its blocks, and publishes new media as it changes', async () => {
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
    published: { at: '2020-01-01T00:00:00Z' },
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

  assert.equal(status, 200, JSON.stringify(body))
  assert.equal(saved.text, edit.text)
  assert.deepEqual(saved.published, published.published)
  assert.deepEqual(saved.media[0], published.media[0])
  assert.equal(saved.media[1].published.key, keyOf(dockMoment.uid, added.uid, 'jpg'))
  assert.equal(saved.media[1].published.video.key, keyOf(dockMoment.uid, added.uid, 'mov', 'video'))
  assert.equal(
    (await upload(env, dockMoment.uid, added.uid, 'image', photo, 'image/jpeg')).status,
    409,
  )
})

test('a Moment of four live photos copies its eight originals two at a time', async () => {
  const { bucket, env } = setUpWithData()
  const draft = {
    ...dockDraft(),
    media: [
      '01M3VDBHC1HW3W1Z58ARMSH201',
      '01M3VDBHC1HW3W1Z58ARMSH202',
      '01M3VDBHC1HW3W1Z58ARMSH203',
      '01M3VDBHC1HW3W1Z58ARMSH204',
    ].map((uid) => ({ uid, type: 'live-photo', alt: '', width: 3024, height: 4032 })),
  }

  for (const item of draft.media) {
    await upload(env, draft.uid, item.uid, 'image', photo, 'image/jpeg')
    await upload(env, draft.uid, item.uid, 'video', motion, 'video/mp4')
  }

  // As R2 does, a put takes a while, long enough for the others to start beside it.
  const put = bucket.put.bind(bucket)
  let running = 0
  let most = 0
  bucket.put = async (...args) => {
    most = Math.max(most, ++running)
    await new Promise((resolve) => setTimeout(resolve, 5))
    running--
    return put(...args)
  }

  const { status, body } = await commit(env, { places: { [made]: dock }, moments: [draft] })

  assert.equal(status, 200, JSON.stringify(body))
  assert.equal(most, 2)
  assert.ok(body.moments[0].media.every((item) => item.published.video))
})

test('a published Moment moves to another day and keeps its media where they are', async () => {
  const { env } = setUpWithData()
  const {
    body: {
      moments: [published],
    },
  } = await publishDock(env)
  const moved = { ...published, occurredAt: '2025-01-05', places: [] }
  const { status, body } = await commit(env, { moments: [moved] })

  assert.equal(status, 200, JSON.stringify(body))
  assert.equal(body.moments[0].occurredAt, '2025-01-05')
  assert.deepEqual(body.moments[0].published, published.published)
  assert.deepEqual(body.moments[0].media, published.media)
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
    // The trips' routes are asked for too, and found none.
    if (new Request(url).url.startsWith('https://valhalla1.openstreetmap.de/')) {
      return Response.json({ error_code: 442 }, { status: 400 })
    }

    calls.push([url, init?.method])
    return new Response(null, { status: 200 })
  }

  try {
    assert.equal((await commit(env, changes)).status, 422, 'the photo is not uploaded yet')
    assert.equal(
      (await get(env, '/v2/places/ce-shi-ma-tou')).status,
      404,
      'and nothing was written',
    )
    assert.equal((await getMoment(env, target.uid)).status, 200)

    await upload(env, draft.uid, draft.media[0].uid, 'image', photo, 'image/jpeg')

    const before = (await get(env, '/v2/rows?limit=1')).body.revision
    const applied = await commit(env, changes, { context })

    await Promise.all(pending)

    assert.equal(applied.status, 200, JSON.stringify(applied.body))
    assert.equal(applied.body.revision, before + 1, 'the revision the write took')
    assert.deepEqual(applied.body.places, { [made]: 'ce-shi-ma-tou' })
    assert.deepEqual(applied.body.deleted, [target.uid], 'one already gone is left out')
    assert.deepEqual(
      applied.body.moments.map((moment) => [moment.uid, moment.places]),
      [
        [draft.uid, ['nanjing']],
        [words.uid, ['ce-shi-ma-tou']],
      ],
    )
    assert.ok(bucket.objects.has(applied.body.moments[0].media[0].published.key))
    assert.deepEqual(calls, [['https://deploy.test/hook', 'POST']], 'the site is built again once')
    assert.equal((await getMoment(env, target.uid)).status, 404)

    const after = await readChanges(env, before)

    assert.deepEqual(after.deleted, [target.uid])
    assert.deepEqual(
      momentsOf(after.entries)
        .map((moment) => moment.uid)
        .sort((first, second) => first.localeCompare(second)),
      [draft.uid, words.uid].sort((first, second) => first.localeCompare(second)),
    )

    const { body: exported } = await get(env, '/v2/export', owner)

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

  const sent = async (body) => (await call(env, 'POST', '/v2/changes', { body })).status

  assert.equal(await sent({ entries: {} }), 422)
  assert.equal(await sent({ entries: [{ parts: { text: 1 } }] }), 422)
  assert.equal(await status({ places: { 'Not An Id': snapshot.places.nanjing } }), 422)
  assert.equal(
    await status({ moments: [{ ...words, places: ['nowhere'] }] }),
    422,
    'an unknown place',
  )
  assert.equal(await status({ moments: [words, words] }), 422, 'one Moment twice')
  assert.equal(await status({ moments: [words], deleted: [words.uid] }), 422, 'saved and deleted')
  assert.equal((await publishMoments(env, { moments: [words] }, { auth: null })).status, 401)
  assert.equal((await getMoment(env, words.uid)).status, 404)
})

test('an original needs the token and a type the API takes', async () => {
  const { env } = setUpWithData()
  const [item] = dockMoment.media

  assert.equal(
    (
      await call(env, 'PUT', `/v2/entries/${dockMoment.uid}/media/${item.uid}/image`, {
        auth: null,
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
    (await call(env, 'PUT', `/v2/entries/${ferryMoment.uid}`, { ...owner, body: ferryMoment }))
      .status,
    405,
  )
  assert.equal(
    (await call(env, 'DELETE', `/v2/entries/${snapshot.moments[0].uid}`, owner)).status,
    405,
  )
  assert.equal(
    (await call(env, 'POST', `/v2/entries/${ferryMoment.uid}/publish`, owner)).status,
    404,
  )
  assert.equal(
    (await call(env, 'PUT', `/v2/places/${place}`, { ...owner, body: dock })).status,
    405,
  )
  assert.equal(
    (
      await call(env, 'PUT', `/v2/collections/${snapshot.trips[0].id}`, {
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
  const before = (await get(env, '/v2/rows?limit=1')).body.revision
  const again = await commit(env, { moments: [published] }, { context })

  assert.equal(again.status, 200)
  assert.deepEqual(again.body.moments, [published])
  assert.ok(!('revision' in again.body), 'and takes no revision')
  assert.equal((await get(env, '/v2/rows?limit=1')).body.revision, before)
  assert.equal(pending.length, 0)
})

test('HEAD on an original says whether it is uploaded, and its MD5', async () => {
  const { env } = setUpWithData()
  const [item] = dockMoment.media
  const path = `/v2/entries/${dockMoment.uid}/media/${item.uid}/image`

  assert.equal((await call(env, 'HEAD', path, { auth: null })).status, 401)

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
