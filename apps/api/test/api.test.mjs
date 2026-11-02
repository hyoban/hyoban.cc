import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { handle } from '../src/index.ts'
import { entrySnapshot, get, readChanges, setUp, token } from './setup.mjs'

// The reads: an import of the made-up data/, and what a client with the token reads afterwards.

const byUid = (first, second) => first.uid.localeCompare(second.uid)
const moments = entrySnapshot.entries.filter((entry) => entry.parts.text)

/** An entry with more words. */
function withWords(entry, more) {
  return {
    ...entry,
    parts: {
      ...entry.parts,
      text: { ...entry.parts.text, text: `${entry.parts.text.text}${more}` },
    },
  }
}

/** What changed from a revision on, every page of the rows read, by kind. */
async function readFeed(env, { since = 0, limit = 100 } = {}) {
  const changes = await readChanges(env, since, { limit })

  return {
    entries: changes.entries,
    deleted: changes.deleted,
    revision: changes.revision,
    places: { places: changes.places, deleted: changes.deletedPlaces },
    collections: { collections: changes.collections, deleted: changes.deletedCollections },
  }
}

test('an import comes back out of the export exactly as written', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(entrySnapshot)

  const { status, body } = await get(env, '/v2/export')

  assert.equal(status, 200)
  assert.equal(body.revision, 1)
  assert.deepEqual(body.manifest, entrySnapshot.manifest)
  assert.deepEqual(body.places, entrySnapshot.places)
  assert.deepEqual(body.entries, [...entrySnapshot.entries].sort(byUid))
  assert.deepEqual([...body.collections].sort(byUid), [...entrySnapshot.collections].sort(byUid))
  assert.deepEqual(body.routes, entrySnapshot.routes)
})

test('the feed pages through every entry, drafts included', async () => {
  const { env, importSnapshot } = setUp()
  const [first, second] = moments
  const { published: _, ...draft } = first

  importSnapshot({
    ...entrySnapshot,
    entries: entrySnapshot.entries.map((entry) => (entry.uid === draft.uid ? draft : entry)),
  })

  const feed = await readFeed(env, { limit: 5 })

  assert.equal(feed.entries.length, entrySnapshot.entries.length)
  assert.ok(feed.entries.some((entry) => entry.uid === draft.uid))
  assert.deepEqual((await get(env, `/v2/entries/${draft.uid}`)).body, draft)
  assert.deepEqual((await get(env, `/v2/entries/${second.uid}`)).body, second)
})

test('importing again hands a client that synced only what changed', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(entrySnapshot)

  const { revision } = await readFeed(env)
  const [removed, edited] = moments
  const changed = withWords(edited, '\n\n补一句。')
  const entries = entrySnapshot.entries
    .filter((entry) => entry.uid !== removed.uid)
    .map((entry) => (entry.uid === edited.uid ? changed : entry))

  importSnapshot({ ...entrySnapshot, entries }, '2026-10-20T08:00:00Z')

  const since = await readFeed(env, { since: revision })

  assert.deepEqual(since.entries, [changed])
  assert.deepEqual(since.deleted, [removed.uid])
  assert.equal((await get(env, `/v2/entries/${removed.uid}`)).status, 404)

  importSnapshot({ ...entrySnapshot, entries }, '2026-10-21T08:00:00Z')

  const unchanged = await readFeed(env, { since: since.revision })

  assert.deepEqual(unchanged, {
    entries: [],
    deleted: [],
    revision: since.revision + 1,
    places: { places: {}, deleted: [] },
    collections: { collections: [], deleted: [] },
  })
})

test('rows from the start, page after page, have what was removed as deleted', async () => {
  const { env, importSnapshot } = setUp()
  const [removed] = moments
  const [placeId] = Object.keys(entrySnapshot.places)
  const { [placeId]: _, ...places } = entrySnapshot.places
  const [gone, ...collections] = entrySnapshot.collections
  const entries = entrySnapshot.entries.filter((entry) => entry.uid !== removed.uid)

  importSnapshot(entrySnapshot)
  importSnapshot({ ...entrySnapshot, places, collections, entries }, '2026-10-20T08:00:00Z')

  const fresh = await readFeed(env, { limit: 3 })

  assert.equal(fresh.entries.length, entries.length)
  assert.deepEqual(fresh.deleted, [removed.uid])
  assert.deepEqual(fresh.places.deleted, [placeId])
  assert.deepEqual(fresh.collections.deleted, [gone.slug])
  assert.ok(!(placeId in fresh.places.places))
  assert.ok(!fresh.collections.collections.some((collection) => collection.uid === gone.uid))

  const since = await readFeed(env, { since: 1 })

  assert.deepEqual(since.deleted, [removed.uid], 'a feed after a revision still has them')
})

test('the feed hands out the places and collections whole from the start, then only what changed', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(entrySnapshot)

  const all = await readFeed(env, { limit: 4 })

  assert.deepEqual(all.places.places, entrySnapshot.places)
  assert.equal(all.collections.collections.length, entrySnapshot.collections.length)

  const [placeId, place] = Object.entries(entrySnapshot.places)[0]
  const [gone, moved, ...kept] = entrySnapshot.collections
  const changedCollection = { ...moved, title: `${moved.title}（改）` }

  importSnapshot(
    {
      ...entrySnapshot,
      places: { ...entrySnapshot.places, [placeId]: { ...place, name: `${place.name}２` } },
      collections: [changedCollection, ...kept],
    },
    '2026-10-20T08:00:00Z',
  )

  const since = await readFeed(env, { since: all.revision })

  assert.deepEqual(since.places, {
    places: { [placeId]: { ...place, name: `${place.name}２` } },
    deleted: [],
  })
  assert.deepEqual(since.collections, { collections: [changedCollection], deleted: [gone.slug] })
})

test('rows read a page at a time stay at their first page’s revision', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(entrySnapshot)

  const first = (await get(env, '/v2/rows?limit=4')).body
  const [edited] = moments
  const changed = withWords(edited, '！')

  importSnapshot(
    {
      ...entrySnapshot,
      entries: entrySnapshot.entries.map((entry) => (entry.uid === edited.uid ? changed : entry)),
    },
    '2026-10-20T08:00:00Z',
  )

  const rows = []
  let path = `/v2/rows?cursor=${first.next}&limit=4`

  while (path) {
    const { body } = await get(env, path)

    assert.equal(body.revision, first.revision)
    rows.push(...body.rows)
    path = body.next && `/v2/rows?cursor=${body.next}&limit=4`
  }

  assert.ok(rows.every((row) => row.rev <= first.revision))
  assert.ok(!rows.some((row) => row.record.parts?.text?.text === changed.parts.text.text))
})

test('places and collections are read whole or one at a time', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(entrySnapshot)

  const [placeId, place] = Object.entries(entrySnapshot.places)[0]
  const [collection] = entrySnapshot.collections

  assert.deepEqual((await get(env, '/v2/places')).body.places, entrySnapshot.places)
  assert.deepEqual((await get(env, `/v2/places/${placeId}`)).body, place)
  assert.equal(
    (await get(env, '/v2/collections')).body.collections.length,
    entrySnapshot.collections.length,
  )
  assert.deepEqual((await get(env, `/v2/collections/${collection.slug}`)).body, collection)
  assert.equal((await get(env, '/v2/places/nowhere-at-all')).status, 404)
})

test('every request needs a token, and methods, paths and queries are checked', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot(entrySnapshot)

  for (const path of ['/', '/v2/export', '/v2/rows', '/v2/places', '/v2/tracks']) {
    const { status, body } = await get(env, path, { auth: null })

    assert.equal(status, 401, path)
    assert.match(body.error, /needs a token/)
  }

  assert.equal((await get(env, '/v2/rows', { auth: 'wrong' })).status, 401)
  assert.equal(
    (
      await handle(
        new Request('https://api.test/v2/rows', { headers: { Authorization: 'Basic x' } }),
        env,
      )
    ).status,
    401,
  )

  for (const path of ['/v1/changes', '/v1/export', '/v1/places', '/v2/moments', '/v2/trips']) {
    assert.equal((await get(env, path)).status, 404, path)
  }

  assert.equal((await get(env, '/v2/entries')).status, 404)
  assert.equal((await get(env, '/v2/rows?limit=0')).status, 400)
  assert.equal((await get(env, '/v2/rows?cursor=nope')).status, 400)
  assert.equal((await get(env, '/v2/changes')).status, 405, 'the change feed is read as rows')
  assert.equal(
    (
      await handle(
        new Request('https://api.test/v2/places', {
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

test('POST /v2/build has the site built again', async () => {
  const { env } = setUp()
  const realFetch = globalThis.fetch
  const calls = []
  const build = (headers = {}) =>
    handle(new Request('https://api.test/v2/build', { method: 'POST', headers }), env)

  globalThis.fetch = async (url, init) => {
    calls.push([url, init?.method])
    return new Response(null, { status: 200 })
  }

  try {
    assert.equal((await build()).status, 401)

    const withoutHook = await build({ Authorization: `Bearer ${token}` })

    assert.equal(withoutHook.status, 202)
    assert.deepEqual(await withoutHook.json(), { started: false })

    env.DEPLOY_HOOK_URL = 'https://deploy.test/hook'
    const started = await build({ Authorization: `Bearer ${token}` })

    assert.deepEqual(await started.json(), { started: true })
    assert.deepEqual(calls, [['https://deploy.test/hook', 'POST']])
  } finally {
    globalThis.fetch = realFetch
  }
})
