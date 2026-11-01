import { tables, type TableName } from '@hyoban/data-spec/tables'
import { readAll, revisionQuery, toRevision, type Database } from './database.ts'
import { readCollectionRow } from './entries.ts'
import { REVISION } from './rows.ts'
import { toEntry } from './writes.ts'

// `GET /v2/rows`: every row the API keeps, table by table, as @hyoban/data-spec/tables has
// the tables, for the app to keep a copy of D1 row for row: its key, the revision it last
// changed at, when it was deleted and its record. Deleted rows come too, from the start as
// after a revision, so the copy has every row D1 has. Every page is of the revision the
// first was read at; a row changed since is left for the next read after it.

// D1 still keeps entries and collections as Moments and trips beside them (rows.ts), so
// until it moves over, each table is read from the one it is kept in, its record the second
// shape's, with the first shape's to work it out from when the row has none yet.
const SOURCES: Record<TableName, string> = {
  places: `SELECT id AS key, rev, deleted_at, record, NULL AS first FROM places`,
  routes: `SELECT key, rev, deleted_at, record, NULL AS first FROM routes`,
  collections: `SELECT id AS key, rev, deleted_at, collection AS record, record AS first FROM trips`,
  entries: `SELECT uid AS key, rev, deleted_at, entry AS record, record AS first FROM moments`,
  tracks: `SELECT uid AS key, rev, deleted_at, record, NULL AS first FROM tracks`,
  vehicle_months: `SELECT key, rev, deleted_at, record, NULL AS first FROM vehicle_months`,
  vehicle_assisted: `SELECT key, rev, deleted_at, record, NULL AS first FROM vehicle_assisted`,
  vehicle_captures: `SELECT id AS key, rev, deleted_at, record, NULL AS first FROM vehicle_captures`,
}

/** Where a read of the rows got to: after this row of this table, of revisions after `since` up to `upto`. */
export interface RowsCursor {
  upto: number
  since: number
  table: number
  rev: number
  key: string
}

interface SourceRow {
  t: number
  key: string
  rev: number
  deleted_at: string | null
  record: string | null
  first: string | null
}

export function formatRowsCursor({ upto, since, table, rev, key }: RowsCursor) {
  return btoa(JSON.stringify([upto, since, table, rev, key]))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/, '')
}

/** A cursor from `formatRowsCursor`, or null when it isn't one. */
export function parseRowsCursor(value: string): RowsCursor | null {
  try {
    const parsed: unknown = JSON.parse(atob(value.replaceAll('-', '+').replaceAll('_', '/')))

    if (!Array.isArray(parsed) || parsed.length !== 5) {
      return null
    }

    const [upto, since, table, rev, key] = parsed as unknown[]
    const whole = (value: unknown): value is number => Number.isInteger(value) && Number(value) >= 0

    return whole(upto) &&
      whole(since) &&
      whole(table) &&
      table < tables.length &&
      whole(rev) &&
      typeof key === 'string'
      ? { upto, since, table, rev, key }
      : null
  } catch {
    return null
  }
}

/** A page of the rows that changed after a revision, as JSON text, a table at a time in the spec's order. */
export async function readRows(
  db: Database,
  query: ({ since: number } | { cursor: RowsCursor }) & { limit: number },
): Promise<string> {
  const { limit } = query
  const cursor = 'cursor' in query ? query.cursor : null
  const since = 'cursor' in query ? query.cursor.since : query.since
  const upto = cursor ? String(cursor.upto) : REVISION
  const start = cursor?.table ?? 0
  const selects = tables.slice(start).map(({ name }, offset) => {
    const index = start + offset
    const after = cursor && index === start ? ' AND (rev > ?2 OR (rev = ?2 AND key > ?3))' : ''

    return `SELECT ${index} AS t, * FROM (${SOURCES[name]}) WHERE rev > ?1 AND rev <= ${upto}${after}`
  })
  const [revision, rows] = await readAll(db, [
    revisionQuery(db),
    db
      .prepare(`${selects.join('\nUNION ALL\n')}\nORDER BY t, rev, key LIMIT ?4`)
      .bind(since, cursor?.rev ?? 0, cursor?.key ?? '', limit + 1),
  ])
  const results = rows as SourceRow[]
  const page = results.slice(0, limit)
  const last = page.at(-1)
  const of = cursor ? cursor.upto : toRevision(revision)
  const next =
    results.length > limit && last
      ? formatRowsCursor({ upto: of, since, table: last.t, rev: last.rev, key: last.key })
      : null
  const rowText = (row: SourceRow) => {
    const table = tables[row.t]!.name

    return `{"table":${JSON.stringify(table)},"key":${JSON.stringify(row.key)},"rev":${row.rev},"deletedAt":${JSON.stringify(row.deleted_at)},"record":${recordText(table, row)}}`
  }

  return `{"revision":${of},"rows":[${page.map(rowText).join(',')}],"next":${JSON.stringify(next)}}`
}

/** A row's record in the second shape: as kept, or worked out from the first for a row written before it. */
function recordText(table: TableName, row: SourceRow) {
  if (row.record !== null) {
    return row.record
  }

  switch (table) {
    case 'entries':
      return JSON.stringify(toEntry({ record: row.first!, entry: null }))
    case 'collections':
      return readCollectionRow({ ...row, id: row.key, record: row.first!, collection: null }).text
    default:
      throw new Error(`A row of ${table} has no record.`)
  }
}
