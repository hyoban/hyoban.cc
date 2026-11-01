import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { buildImportSql, buildMergeSql, MAX_STATEMENT } from '../src/import.ts'
import { get, momentsOf, readChanges, setUp, snapshot } from './setup.mjs'

// A merge writes only the records it is given, over what the database has, and removes
// nothing: what the scripts publish outside the API lands next to what the app wrote.

test('a merge writes its records and leaves every other one alone', async () => {
  const { env, importSnapshot, sqlite } = setUp()

  importSnapshot()

  const [first, second] = snapshot.moments
  const changed = { ...first, text: `${first.text}（补）` }
  const place = { name: '新地方', latitude: 1, longitude: 2 }
  const { revision } = (await get(env, '/v2/rows?limit=1')).body

  sqlite.exec(
    buildMergeSql(
      { places: { 'new-place': place }, trips: [], moments: [changed] },
      '2026-10-20T10:00:00Z',
      'hyoban.cc data:push',
    ),
  )

  const since = await readChanges(env, revision)
  const exported = (await get(env, '/v2/export')).body
  const moments = momentsOf(exported.entries)

  assert.deepEqual(momentsOf(since.entries), [changed])
  assert.deepEqual(since.deleted, [])
  assert.equal(moments.length, snapshot.moments.length)
  assert.deepEqual(
    moments.find((moment) => moment.uid === second.uid),
    second,
  )
  assert.deepEqual(exported.places['new-place'], place)
  assert.equal(Object.keys(exported.places).length, Object.keys(snapshot.places).length + 1)
  assert.equal(exported.collections.length, snapshot.trips.length)
  assert.deepEqual(exported.manifest, {
    ...snapshot.manifest,
    generator: 'hyoban.cc data:push',
    updatedAt: '2026-10-20T10:00:00Z',
  })
})

test('an import keeps more keys than one statement could list, and drops the rest', () => {
  const { sqlite, importSnapshot } = setUp()
  const many = Object.fromEntries(
    Array.from({ length: 6000 }, (_, index) => [
      `made-up-place-with-a-long-id-${index}`,
      { name: `地点 ${index}`, latitude: 30, longitude: 120 },
    ]),
  )
  const sql = buildImportSql(
    { ...snapshot, places: { ...snapshot.places, ...many } },
    '2026-10-19T12:00:00Z',
  )

  assert.ok(sql.length > MAX_STATEMENT, 'more keys than a statement takes')
  assert.ok(sql.split(';\n').every((statement) => Buffer.byteLength(statement) <= MAX_STATEMENT))
  sqlite.exec(sql)
  importSnapshot()

  const live = sqlite.prepare(`SELECT count(*) AS n FROM places WHERE deleted_at IS NULL`).get().n
  const tables = sqlite.prepare(`SELECT name FROM sqlite_master WHERE name = 'import_kept'`).all()

  assert.equal(live, Object.keys(snapshot.places).length)
  assert.deepEqual(tables, [], 'and leaves nothing behind')
})
