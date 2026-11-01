import assert from 'node:assert/strict'
import { DatabaseSync } from 'node:sqlite'
import { test } from 'vite-plus/test'

import { collectionSchema, entrySchema } from '@hyoban/data-spec/entry'
import { getTableStatements, tables } from '@hyoban/data-spec/tables'
import { call, get, setUp, token } from './setup.mjs'

// `GET /v2/rows`: every row D1 keeps, table by table, for the app's copy to keep row for row.

const owner = { auth: token }

/** Every page of the rows after `since`, `limit` at a time. */
async function readAllRows(env, since, limit = 3) {
  const rows = []
  let path = `/v2/rows?since=${since}&limit=${limit}`
  let revision

  while (path) {
    const { status, body } = await get(env, path)

    assert.equal(status, 200)
    assert.ok(body.rows.length <= limit)
    revision ??= body.revision
    assert.equal(body.revision, revision, 'every page is of the first page’s revision')
    rows.push(...body.rows)
    path = body.next && `/v2/rows?cursor=${body.next}&limit=${limit}`
  }

  return { revision, rows }
}

test('the rows from the start are every row, a table at a time, in the spec’s tables', async () => {
  const { env, importSnapshot, sqlite } = setUp()

  importSnapshot()

  const { rows } = await readAllRows(env, 0)
  const order = tables.map((table) => table.name)
  const count = (table) => sqlite.prepare(`SELECT count(*) AS n FROM ${table}`).get().n
  const of = (table) => rows.filter((row) => row.table === table)

  assert.deepEqual(
    rows.map((row) => order.indexOf(row.table)),
    rows.map((row) => order.indexOf(row.table)).toSorted((a, b) => a - b),
  )
  assert.equal(of('entries').length, count('entries'))
  assert.equal(of('collections').length, count('collections'))
  assert.equal(of('places').length, count('places'))
  assert.equal(of('routes').length, count('routes'))
  of('entries').forEach((row) => assert.equal(entrySchema.parse(row.record).uid, row.key))
  of('collections').forEach((row) => collectionSchema.parse(row.record))

  // What the copy keeps them in takes them as they come.
  const copy = new DatabaseSync(':memory:')

  getTableStatements().forEach((statement) => copy.exec(statement))

  for (const row of rows) {
    copy
      .prepare(`INSERT INTO ${row.table} (key, rev, deleted_at, record) VALUES (?, ?, ?, ?)`)
      .run(row.key, row.rev, row.deletedAt, JSON.stringify(row.record))
  }

  assert.equal(copy.prepare(`SELECT count(*) AS n FROM entries`).get().n, of('entries').length)
})

test('after a revision only what changed comes, deleted rows with when', async () => {
  const { env, importSnapshot } = setUp()

  importSnapshot()

  const { revision, rows } = await readAllRows(env, 0)
  const gone = rows.find((row) => row.table === 'entries' && row.deletedAt === null)
  const { status } = await call(env, 'POST', '/v2/changes', {
    ...owner,
    body: { deleted: [gone.key] },
  })

  assert.equal(status, 200)

  const after = await readAllRows(env, revision)
  const deleted = after.rows.find((row) => row.key === gone.key)

  assert.ok(after.revision > revision)
  assert.ok(after.rows.every((row) => row.rev > revision))
  assert.equal(deleted.table, 'entries')
  assert.ok(deleted.deletedAt)
})

test('a cursor that isn’t one is refused', async () => {
  const { env } = setUp()

  assert.equal((await get(env, '/v2/rows?cursor=nope')).status, 400)
  assert.equal((await get(env, '/v2/rows?since=-1')).status, 400)
})
