import { tables } from '@hyoban/data-spec/tables'
import { readAll, revisionQuery, toRevision, type Database } from './database.ts'
import { REVISION } from './rows.ts'

// `GET /v2/rows`: every row the API keeps, table by table, as @hyoban/data-spec/tables has
// the tables, for the app to keep a copy of D1 row for row: its key, the revision it last
// changed at, when it was deleted and its record. Deleted rows come too, from the start as
// after a revision, so the copy has every row D1 has. Every page is of the revision the
// first was read at; a row changed since is left for the next read after it.

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
  record: string
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
  // A table at a time until the page is full: D1 takes no compound SELECT of every table.
  // What a table read after the first misses, having changed since, has a revision past
  // `upto`, so it is left for the next read as anything changed since is.
  const select = (index: number) => {
    const after = cursor && index === start ? ' AND (rev > ?2 OR (rev = ?2 AND key > ?3))' : ''

    return db
      .prepare(
        `SELECT ${index} AS t, key, rev, deleted_at, record FROM ${tables[index]!.name}
         WHERE rev > ?1 AND rev <= ${upto}${after} ORDER BY rev, key LIMIT ?4`,
      )
      .bind(since, cursor?.rev ?? 0, cursor?.key ?? '', limit + 1)
  }
  const [revision, first] = await readAll(db, [revisionQuery(db), select(start)])
  const of = cursor ? cursor.upto : toRevision(revision)
  const results = first as SourceRow[]

  // Past the first page, `upto` is the number the cursor holds; on it, the revision just read.
  for (let index = start + 1; index < tables.length && results.length <= limit; index++) {
    const rows = await db
      .prepare(
        `SELECT ${index} AS t, key, rev, deleted_at, record FROM ${tables[index]!.name}
         WHERE rev > ?1 AND rev <= ?2 ORDER BY rev, key LIMIT ?3`,
      )
      .bind(since, of, limit + 1 - results.length)
      .all<SourceRow>()

    results.push(...rows.results)
  }

  const page = results.slice(0, limit)
  const last = page.at(-1)
  const next =
    results.length > limit && last
      ? formatRowsCursor({ upto: of, since, table: last.t, rev: last.rev, key: last.key })
      : null
  const rowText = (row: SourceRow) => {
    const table = tables[row.t]!.name

    return `{"table":${JSON.stringify(table)},"key":${JSON.stringify(row.key)},"rev":${row.rev},"deletedAt":${JSON.stringify(row.deleted_at)},"record":${row.record}}`
  }

  return `{"revision":${of},"rows":[${page.map(rowText).join(',')}],"next":${JSON.stringify(next)}}`
}
