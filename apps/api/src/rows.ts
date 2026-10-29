import type { MomentRecord, TripRecord } from '@hyoban/data-spec'
import {
  entryToMoment,
  getCollectionKey,
  getPublicEntry,
  isMarkEntry,
  isMomentEntry,
  mergeMomentIntoEntry,
  type Collection,
  type Entry,
  type PublicTrack,
} from '@hyoban/data-spec/entry'

// How a record becomes a row: the columns each table looks things up by, beside the
// record as written, and the SQL that writes it. Shared by the import, which writes SQL
// text for wrangler, and the Worker's writes, which bind values to statements.

/** The database's revision, inside a statement that bumps it first. */
export const REVISION = `(SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'revision')`

/** Every write starts with this, so the rows it changes take a new revision. */
export const BUMP_REVISION = `UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'`

export type Value = string | number | null
export type Table = 'moments' | 'places' | 'trips' | 'routes' | 'tracks'

export const KEYS: Record<Table, string> = {
  moments: 'uid',
  places: 'id',
  trips: 'id',
  routes: 'key',
  tracks: 'uid',
}

/** What sort of entry a row holds: a Moment the first shape has, a mark, or the rest. */
export type EntryKind = 'moment' | 'mark' | 'entry'

export function getEntryKind(entry: Entry): EntryKind {
  return isMomentEntry(entry) ? 'moment' : isMarkEntry(entry) ? 'mark' : 'entry'
}

/**
 * An entry's row, in both shapes: `record` is the Moment the first shape reads (as written,
 * when it came as one), or the entry itself for one the first shape has none for; `shown`
 * what the public sees.
 */
export function entryRow(
  entry: Entry,
  { record, trip }: { record?: unknown; trip?: string | undefined } = {},
): Record<string, Value> {
  const kind = getEntryKind(entry)
  const shown = getPublicEntry(entry)

  return {
    uid: entry.uid,
    occurred_at: entry.at,
    day: entry.at.slice(0, 10),
    published: entry.published ? 1 : 0,
    trip: trip ?? null,
    hidden: entry.visibility === 'private' ? 1 : 0,
    kind,
    record: JSON.stringify(kind === 'moment' ? (record ?? entryToMoment(entry, trip)) : entry),
    entry: JSON.stringify(entry),
    shown: shown ? JSON.stringify(shown) : null,
  }
}

/** A Moment's row, as the first shape writes one, over the entry it was. */
export function momentRow(
  moment: MomentRecord,
  record: unknown = moment,
  prior?: Entry,
): Record<string, Value> {
  return entryRow(mergeMomentIntoEntry(prior, moment), { record, trip: moment.trip })
}

/** A collection's row, with the trip the first shape reads; not shown when private. */
export function collectionRow(collection: Collection, trip: TripRecord, record: unknown = trip) {
  return {
    id: getCollectionKey(collection),
    start_date: collection.span.start.slice(0, 10),
    end_date: collection.span.end.slice(0, 10),
    uid: collection.uid,
    record: JSON.stringify(record),
    collection: JSON.stringify(collection),
    shown: collection.visibility === 'private' ? null : JSON.stringify(collection),
  } satisfies Record<string, Value>
}

export function trackRow(track: PublicTrack): Record<string, Value> {
  return {
    uid: track.uid,
    start_at: track.start,
    end_at: track.end,
    collection: track.collection ?? null,
    record: JSON.stringify(track),
  }
}

export function placeRow(id: string, record: unknown): Record<string, Value> {
  return { id, record: JSON.stringify(record) }
}

export function routeRow(key: string, record: unknown): Record<string, Value> {
  return { key, record: JSON.stringify(record) }
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
  // Either shape changing is a change.
  const compared = ['record', 'entry', 'collection'].filter((column) => column in values)

  return `INSERT INTO ${table} (${Object.keys(columns).join(', ')}) VALUES (${Object.values(columns).join(', ')})
  ON CONFLICT (${key}) DO UPDATE SET ${updates.join(', ')}
  WHERE ${compared.map((column) => `${table}.${column} IS NOT excluded.${column}`).join(' OR ')} OR ${table}.deleted_at IS NOT NULL`
}

/** The placeholders `?1`, `?2`… for a row's values, in order, for `upsertSql`. */
export function placeholders(row: Record<string, Value>) {
  return Object.fromEntries(Object.keys(row).map((column, index) => [column, `?${index + 1}`]))
}
