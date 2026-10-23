import type { MomentRecord, TripRecord } from '@hyoban/data-spec'

// How a record becomes a row: the columns each table looks things up by, beside the
// record as written, and the SQL that writes it. Shared by the import, which writes SQL
// text for wrangler, and the Worker's writes, which bind values to statements.

/** The database's revision, inside a statement that bumps it first. */
export const REVISION = `(SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'revision')`

/** Every write starts with this, so the rows it changes take a new revision. */
export const BUMP_REVISION = `UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'`

export type Value = string | number | null
export type Table = 'moments' | 'places' | 'trips' | 'routes'

export const KEYS: Record<Table, string> = {
  moments: 'uid',
  places: 'id',
  trips: 'id',
  routes: 'key',
}

export function momentRow(moment: MomentRecord, record: unknown = moment): Record<string, Value> {
  return {
    uid: moment.uid,
    occurred_at: moment.occurredAt,
    day: moment.occurredAt.slice(0, 10),
    published: moment.published ? 1 : 0,
    trip: moment.trip ?? null,
    hidden: moment.hidden ? 1 : 0,
    record: JSON.stringify(record),
  }
}

export function placeRow(id: string, record: unknown): Record<string, Value> {
  return { id, record: JSON.stringify(record) }
}

export function routeRow(key: string, record: unknown): Record<string, Value> {
  return { key, record: JSON.stringify(record) }
}

export function tripRow(trip: TripRecord, record: unknown = trip): Record<string, Value> {
  return { id: trip.id, start_date: trip.start, end_date: trip.end, record: JSON.stringify(record) }
}

/**
 * Writes a row, given its values as SQL expressions, at the current revision; a row whose
 * record didn't change, and isn't coming back from deleted, keeps its revision.
 */
export function upsertSql(table: Table, values: Record<string, string>) {
  const key = KEYS[table]
  const columns = { ...values, rev: REVISION, deleted_at: 'NULL' }
  const updates = Object.keys(columns)
    .filter((column) => column !== key)
    .map((column) => `${column} = excluded.${column}`)

  return `INSERT INTO ${table} (${Object.keys(columns).join(', ')}) VALUES (${Object.values(columns).join(', ')})
  ON CONFLICT (${key}) DO UPDATE SET ${updates.join(', ')}
  WHERE ${table}.record IS NOT excluded.record OR ${table}.deleted_at IS NOT NULL`
}

/** The placeholders `?1`, `?2`… for a row's values, in order, for `upsertSql`. */
export function placeholders(row: Record<string, Value>) {
  return Object.fromEntries(Object.keys(row).map((column, index) => [column, `?${index + 1}`]))
}
