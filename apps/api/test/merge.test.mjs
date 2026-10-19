import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { buildMergeSql } from '../src/import.ts'
import { get, setUp, snapshot, token } from './setup.mjs'

// A merge writes only the records it is given, over what the database has, and removes
// nothing: what the scripts publish outside the API lands next to what the app wrote.

test('a merge writes its records and leaves every other one alone', async () => {
  const { env, importSnapshot, sqlite } = setUp()

  importSnapshot()

  const [first, second] = snapshot.moments
  const changed = { ...first, text: `${first.text}（补）` }
  const place = { name: '新地方', latitude: 1, longitude: 2 }
  const { revision } = (await get(env, '/v1/moments?limit=1')).body

  sqlite.exec(
    buildMergeSql(
      { places: { 'new-place': place }, trips: [], moments: [changed] },
      '2026-10-20T10:00:00Z',
      'hyoban.cc data:push',
    ),
  )

  const since = (await get(env, `/v1/moments?since=${revision}`, { auth: token })).body
  const exported = (await get(env, '/v1/export', { auth: token })).body

  assert.deepEqual(since.moments, [changed])
  assert.deepEqual(since.deleted, [])
  assert.equal(exported.moments.length, snapshot.moments.length)
  assert.deepEqual(
    exported.moments.find((moment) => moment.uid === second.uid),
    second,
  )
  assert.deepEqual(exported.places['new-place'], place)
  assert.equal(Object.keys(exported.places).length, Object.keys(snapshot.places).length + 1)
  assert.equal(exported.trips.length, snapshot.trips.length)
  assert.deepEqual(exported.manifest, {
    ...snapshot.manifest,
    generator: 'hyoban.cc data:push',
    updatedAt: '2026-10-20T10:00:00Z',
  })
})
