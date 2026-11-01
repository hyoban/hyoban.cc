import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import { test } from 'vite-plus/test'

// The migrations end at the tables @hyoban/data-spec/tables defines (schema.sql), which the
// app's copy keeps too, and the one that moved the records over keeps every row.

const migrations = new URL('../migrations/', import.meta.url)
const files = readdirSync(migrations).sort()

function migrate(upto = files.length) {
  const db = new DatabaseSync(':memory:')

  for (const file of files.slice(0, upto)) {
    db.exec(readFileSync(new URL(file, migrations), 'utf8'))
  }

  return db
}

/** The tables and indexes as SQLite keeps them, but for the server's own bookkeeping. */
function shape(db) {
  return db
    .prepare(
      `SELECT type, name, sql FROM sqlite_master
       WHERE sql IS NOT NULL AND tbl_name NOT IN ('meta', 'telegram_posts') ORDER BY name`,
    )
    .all()
    .map((row) => ({ ...row }))
}

test('the migrations make the tables schema.sql has', () => {
  const schema = new DatabaseSync(':memory:')

  schema.exec(readFileSync(new URL('../schema.sql', import.meta.url), 'utf8'))
  assert.deepEqual(shape(migrate()), shape(schema))
})

test('moving to the shared tables keeps every row, its revision and its deletion', () => {
  const db = migrate(files.indexOf('0008_shared_tables.sql'))
  const entry = (uid, at, parts) => JSON.stringify({ uid, at, parts, createdAt: at, updatedAt: at })
  const at = '2026-10-29T12:00:00+08:00'

  db.exec(`
    INSERT INTO places (id, rev, record) VALUES ('nanjing', 1, '{"name":"南京"}');
    INSERT INTO trips (id, start_date, end_date, rev, deleted_at, record, uid, collection) VALUES
      ('2026-test', '2026-10-29', '2026-10-30', 2, NULL, '{}', '01K8PZ0000000000000000TRIP',
       '{"uid":"01K8PZ0000000000000000TRIP","slug":"2026-test","span":{"start":"2026-10-29","end":"2026-10-30"}}');
    INSERT INTO moments (uid, occurred_at, day, rev, deleted_at, record, kind, entry) VALUES
      ('01K8PZ00000000000000MOMENT', '${at}', '2026-10-29', 3, NULL, '{}', 'moment',
       '${entry('01K8PZ00000000000000MOMENT', at, { text: { text: '你好' } })}'),
      ('01K8PZ000000000000000MARK0', '${at}', '2026-10-29', 4, NULL, '{}', 'mark',
       '${entry('01K8PZ000000000000000MARK0', at, {})}'),
      ('01K8PZ000000000000000GONE0', '2025-01-01T08:00:00+08:00', '2025-01-01', 5,
       '2025-02-01T00:00:00Z', '{"createdAt":"2025-01-01T00:00:00Z"}', 'moment', NULL);
    INSERT INTO tracks (uid, start_at, end_at, rev, record) VALUES
      ('01K8PZ0000000000000TRACK0', '${at}', '${at}', 6, '{"start":"${at}","end":"${at}"}');
    INSERT INTO vehicle_captures (id, rev, record) VALUES ('abc', 7, '{}');
  `)
  db.exec(readFileSync(new URL('0008_shared_tables.sql', migrations), 'utf8'))

  const rows = (table, columns = 'key, rev, deleted_at') =>
    db
      .prepare(`SELECT ${columns} FROM ${table} ORDER BY rev`)
      .all()
      .map((row) => ({ ...row }))

  assert.deepEqual(rows('places'), [{ key: 'nanjing', rev: 1, deleted_at: null }])
  assert.deepEqual(rows('collections', 'key, rev, uid, start_day'), [
    { key: '2026-test', rev: 2, uid: '01K8PZ0000000000000000TRIP', start_day: '2026-10-29' },
  ])
  assert.deepEqual(rows('entries', 'key, rev, deleted_at, kind, day'), [
    {
      key: '01K8PZ00000000000000MOMENT',
      rev: 3,
      deleted_at: null,
      kind: 'moment',
      day: '2026-10-29',
    },
    {
      key: '01K8PZ000000000000000MARK0',
      rev: 4,
      deleted_at: null,
      kind: 'mark',
      day: '2026-10-29',
    },
    {
      key: '01K8PZ000000000000000GONE0',
      rev: 5,
      deleted_at: '2025-02-01T00:00:00Z',
      kind: 'mark',
      day: '2025-01-01',
    },
  ])
  assert.deepEqual(
    JSON.parse(
      db.prepare(`SELECT record FROM entries WHERE key = '01K8PZ000000000000000GONE0'`).get()
        .record,
    ),
    {
      uid: '01K8PZ000000000000000GONE0',
      at: '2025-01-01T08:00:00+08:00',
      parts: {},
      createdAt: '2025-01-01T00:00:00Z',
      updatedAt: '2025-02-01T00:00:00Z',
    },
  )
  assert.deepEqual(rows('tracks', 'key, rev, start_at'), [
    { key: '01K8PZ0000000000000TRACK0', rev: 6, start_at: at },
  ])
  assert.deepEqual(rows('vehicle_captures'), [{ key: 'abc', rev: 7, deleted_at: null }])
})

test('a live Moment with no second shape stops the move', () => {
  const db = migrate(files.indexOf('0008_shared_tables.sql'))

  db.exec(`INSERT INTO moments (uid, occurred_at, day, rev, record) VALUES
    ('01K8PZ00000000000000MOMENT', '2026-10-29', '2026-10-29', 1, '{}')`)
  assert.throws(
    () => db.exec(readFileSync(new URL('0008_shared_tables.sql', migrations), 'utf8')),
    /NOT NULL/,
  )
})
