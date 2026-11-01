import type { VehicleAssistedDrivingCapture, VehicleCapture, VehicleMonth } from '@hyoban/data-spec'
import {
  getCollectionKey,
  isMarkEntry,
  isMomentEntry,
  type Collection,
  type Entry,
  type PublicTrack,
} from '@hyoban/data-spec/entry'
import type { TableName } from '@hyoban/data-spec/tables'

// How a record becomes a row of the tables @hyoban/data-spec/tables defines, the same in
// D1 and in the app's copy: its key and the record as written; SQLite works the other
// columns out from the record. Shared by the import, which writes SQL text for wrangler,
// and the Worker's writes, which bind values to statements.

/** The database's revision, inside a statement that bumps it first. */
export const REVISION = `(SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'revision')`

/** Every write starts with this, so the rows it changes take a new revision. */
export const BUMP_REVISION = `UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'`

export type Value = string | number | null
export type Table = TableName

/** A row as written: its key and its record's JSON. */
export interface Row {
  key: string
  record: string
}

/** What sort of entry it is, as the `kind` column says: a Moment the first shape has, a mark, or the rest. */
export type EntryKind = 'moment' | 'mark' | 'entry'

export function getEntryKind(entry: Entry): EntryKind {
  return isMomentEntry(entry) ? 'moment' : isMarkEntry(entry) ? 'mark' : 'entry'
}

const row = (key: string, record: unknown): Row => ({ key, record: JSON.stringify(record) })

export const entryRow = (entry: Entry) => row(entry.uid, entry)
export const collectionRow = (collection: Collection) =>
  row(getCollectionKey(collection), collection)
export const trackRow = (track: PublicTrack) => row(track.uid, track)
export const placeRow = (id: string, record: unknown) => row(id, record)
export const routeRow = (key: string, record: unknown) => row(key, record)

// The car's statistics, as the Zeekr import normalised them: a month, a cumulative
// assisted-driving observation, and a source capture they point into.

export const vehicleMonthRow = (month: VehicleMonth) =>
  row(`${month.deviceId}/${month.month}`, month)
export const vehicleAssistedRow = (observation: VehicleAssistedDrivingCapture) =>
  row(`${observation.deviceId}/${observation.capturedAt}`, observation)
export const vehicleCaptureRow = (capture: VehicleCapture) => row(capture.id, capture)

/**
 * Writes a row, given its key and record as SQL expressions, at the current revision; a row
 * whose record didn't change, and isn't coming back from deleted, keeps its revision.
 */
export function upsertSql(table: Table, { key, record }: { key: string; record: string }) {
  return `INSERT INTO ${table} (key, rev, deleted_at, record) VALUES (${key}, ${REVISION}, NULL, ${record})
  ON CONFLICT (key) DO UPDATE SET rev = excluded.rev, deleted_at = NULL, record = excluded.record
  WHERE ${table}.record IS NOT excluded.record OR ${table}.deleted_at IS NOT NULL`
}

/** Marks a row deleted at the current revision, if it isn't already. */
export function deleteSql(table: Table) {
  return `UPDATE ${table} SET deleted_at = ?1, rev = ${REVISION} WHERE key = ?2 AND deleted_at IS NULL`
}
