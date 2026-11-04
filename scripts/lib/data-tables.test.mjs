import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { DatabaseSync } from 'node:sqlite'
import { fileURLToPath } from 'node:url'
import { test } from 'vite-plus/test'

import {
  collectionSchema,
  entrySchema,
  getCollectionKey,
  isMarkEntry,
  isMomentEntry,
} from '@hyoban/data-spec/entry'
import { copiedTables, getTableStatements } from '@hyoban/data-spec/tables'
import { listJsonFiles, readJson } from '../../apps/web/src/data/store.ts'
import {
  kotlinFile,
  renderKotlin,
  renderSql,
  renderSwift,
  sqlFile,
  swiftFile,
} from './data-tables.mjs'

// D1 and the apps' copies keep the same tables, so what is generated for each must be current,
// and the columns SQLite works out from a record must say what the spec's own rules say.

const fixtureRoot = fileURLToPath(
  new URL('../../packages/data-spec/test/fixtures/data/', import.meta.url),
)

test('the tables for D1 and the app are generated from the current spec', async () => {
  assert.equal(await readFile(sqlFile, 'utf8'), renderSql())
  assert.equal(await readFile(kotlinFile, 'utf8'), renderKotlin())
  assert.equal(await readFile(swiftFile, 'utf8'), renderSwift())
})

test('the Kotlin strings read back as the same statements', () => {
  const statements = [...renderKotlin().matchAll(/"""\n([\s\S]*?)\n\s*"""/g)].map(([, text]) =>
    text.replaceAll(/^ {8}/gm, '').replaceAll("${'$'}", '$'),
  )

  assert.deepEqual(statements, getTableStatements(copiedTables))
  assert.ok(!copiedTables.some((table) => table.name === 'routes'), 'the routes are D1’s alone')
})

test('the Swift strings read back as the same statements', () => {
  const statements = [...renderSwift().matchAll(/"""\n([\s\S]*?)\n\s*"""/g)].map(([, text]) =>
    text.replaceAll(/^ {8}/gm, ''),
  )

  assert.deepEqual(statements, getTableStatements(copiedTables))
})

test("the worked-out columns agree with the spec's rules", () => {
  const db = new DatabaseSync(':memory:')

  for (const statement of getTableStatements()) {
    db.exec(statement)
  }

  const entries = listJsonFiles('entries', fixtureRoot).map((file) =>
    entrySchema.parse(readJson(file, fixtureRoot)),
  )
  const collections = listJsonFiles('collections', fixtureRoot).map((file) =>
    collectionSchema.parse(readJson(file, fixtureRoot)),
  )
  const insert = (table, key, record) =>
    db
      .prepare(`INSERT INTO ${table} (key, rev, record) VALUES (?, 1, ?)`)
      .run(key, JSON.stringify(record))

  entries.forEach((entry) => insert('entries', entry.uid, entry))
  collections.forEach((collection) =>
    insert('collections', getCollectionKey(collection), collection),
  )

  const kinds = new Set()

  for (const entry of entries) {
    const row = db
      .prepare(`SELECT at, day, kind, place, visibility, published_at FROM entries WHERE key = ?`)
      .get(entry.uid)
    const kind = isMomentEntry(entry) ? 'moment' : isMarkEntry(entry) ? 'mark' : 'entry'

    kinds.add(kind)
    assert.deepEqual(
      { ...row },
      {
        at: entry.at,
        day: entry.at.slice(0, 10),
        kind,
        place: entry.place ?? null,
        visibility: entry.visibility ?? null,
        published_at: entry.published?.at ?? null,
      },
      entry.uid,
    )
  }

  assert.ok(kinds.has('moment') && kinds.has('mark'), 'the fixture has Moments and marks')

  for (const collection of collections) {
    const row = db
      .prepare(`SELECT uid, start_day, end_day FROM collections WHERE key = ?`)
      .get(getCollectionKey(collection))

    assert.deepEqual(
      { ...row },
      {
        uid: collection.uid,
        start_day: collection.span.start.slice(0, 10),
        end_day: collection.span.end.slice(0, 10),
      },
    )
  }
})
