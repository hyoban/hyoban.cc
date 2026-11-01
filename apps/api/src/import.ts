import {
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  routesSchema,
  tripRecordSchema,
  vehicleAssistedDrivingCaptureSchema,
  vehicleCaptureSchema,
  vehicleMonthSchema,
} from '@hyoban/data-spec'
import {
  collectionSchema,
  entrySchema,
  momentToEntry,
  publicTrackSchema,
  tripToCollection,
} from '@hyoban/data-spec/entry'
import {
  BUMP_REVISION,
  REVISION,
  collectionRow,
  entryRow,
  placeRow,
  routeRow,
  trackRow,
  upsertSql,
  vehicleAssistedRow,
  vehicleCaptureRow,
  vehicleMonthRow,
  type Row,
  type Table,
} from './rows.ts'

// Turns records, as `data/` keeps them, into SQL for the database. A snapshot import
// makes it hold exactly the snapshot: new and changed records are written and records
// missing from it are marked deleted (`pnpm data:import`, to restore the backup). A
// merge only writes the records it is given, for what the scripts publish outside the
// API (`pnpm data:push`). Either way only rows that change take the new revision, so a
// client syncing afterwards gets just the difference. Runs in Node, not in the Worker.
//
// The records come in either shape: Moments and trips, or entries, collections and public
// tracks. Either is written as entries and collections, the tables
// @hyoban/data-spec/tables defines.

export interface ImportSnapshot {
  manifest: unknown
  places: Record<string, unknown>
  trips?: unknown[]
  moments?: unknown[]
  entries?: unknown[]
  collections?: unknown[]
  tracks?: unknown[]
  /** The legs' routes; a backup from before they were kept leaves the database's alone. */
  routes?: Record<string, unknown>
  /** The car's statistics; a backup without them leaves the database's alone. */
  vehicleMonths?: unknown[]
  vehicleAssisted?: unknown[]
  vehicleCaptures?: unknown[]
}

export function buildImportSql(snapshot: ImportSnapshot, now: string): string {
  manifestSchema.parse(snapshot.manifest)
  placesSchema.parse(snapshot.places)

  const statements = [
    BUMP_REVISION,
    `INSERT INTO meta (key, value) VALUES ('manifest', ${quote(JSON.stringify(snapshot.manifest))})
  ON CONFLICT (key) DO UPDATE SET value = excluded.value`,
  ]

  for (const [id, place] of Object.entries(snapshot.places)) {
    statements.push(upsert('places', placeRow(id, place)))
  }

  statements.push(remove('places', Object.keys(snapshot.places), now))

  const rows = toRows(snapshot)

  for (const table of ['collections', 'entries', 'tracks'] as const) {
    const kept = rows[table]

    if (kept) {
      statements.push(...kept.map((row) => upsert(table, row)))
      statements.push(remove(table, keysOf(kept), now))
    }
  }

  if (snapshot.routes) {
    routesSchema.parse(snapshot.routes)

    for (const [key, route] of Object.entries(snapshot.routes)) {
      statements.push(upsert('routes', routeRow(key, route)))
    }

    statements.push(remove('routes', Object.keys(snapshot.routes), now))
  }

  for (const { table, rows } of toVehicleRows(snapshot)) {
    statements.push(...rows.map((row) => upsert(table, row)))
    statements.push(remove(table, keysOf(rows), now))
  }

  return `${statements.join(';\n')};\n`
}

export interface MergeRecords {
  places: Record<string, unknown>
  trips?: unknown[]
  moments?: unknown[]
  entries?: unknown[]
  collections?: unknown[]
  tracks?: unknown[]
  vehicleMonths?: unknown[]
  vehicleAssisted?: unknown[]
  vehicleCaptures?: unknown[]
}

/**
 * Writes these records over whatever the database has for them, leaving every other
 * record alone, and stamps the manifest with `generator` and `now`. Entries a collection
 * is worked out from must come with it: its marks, and the Moments it includes.
 */
export function buildMergeSql(records: MergeRecords, now: string, generator: string): string {
  placesSchema.parse(records.places)

  const rows = toRows(records)
  const statements = [
    BUMP_REVISION,
    `UPDATE meta SET value = json_set(value, '$.generator', ${quote(generator)}, '$.updatedAt', ${quote(now)}) WHERE key = 'manifest'`,
    ...Object.entries(records.places).map(([id, place]) => upsert('places', placeRow(id, place))),
    ...rows.collections.map((row) => upsert('collections', row)),
    ...rows.entries.map((row) => upsert('entries', row)),
    ...(rows.tracks ?? []).map((row) => upsert('tracks', row)),
    ...toVehicleRows(records).flatMap(({ table, rows }) => rows.map((row) => upsert(table, row))),
  ]

  return `${statements.join(';\n')};\n`
}

/** The entries, collections and public tracks of either shape's records. */
function toRows(records: Omit<MergeRecords, 'places'>) {
  // The first shape: Moments, and trips with their waypoints as marks.
  const moments = (records.moments ?? []).map((raw) => momentRecordSchema.parse(raw))
  const entries = moments.map((moment) => entrySchema.parse(momentToEntry(moment)))
  const collections = (records.trips ?? []).map((raw) => {
    const { collection, marks } = tripToCollection(tripRecordSchema.parse(raw), moments)

    entries.push(...marks.map((mark) => entrySchema.parse(mark)))

    return collectionSchema.parse(collection)
  })

  entries.push(...(records.entries ?? []).map((raw) => entrySchema.parse(raw)))
  collections.push(...(records.collections ?? []).map((raw) => collectionSchema.parse(raw)))

  return {
    entries: entries.map(entryRow),
    collections: collections.map(collectionRow),
    tracks: records.tracks?.map((raw) => trackRow(publicTrackSchema.parse(raw))),
  }
}

/** The car's statistics given, by table; a kind left out isn't touched. */
function toVehicleRows(records: {
  vehicleMonths?: unknown[]
  vehicleAssisted?: unknown[]
  vehicleCaptures?: unknown[]
}) {
  const tables: { table: Table; rows: Row[] }[] = []

  if (records.vehicleMonths) {
    tables.push({
      table: 'vehicle_months',
      rows: records.vehicleMonths.map((raw) => vehicleMonthRow(checked(vehicleMonthSchema, raw))),
    })
  }

  if (records.vehicleAssisted) {
    tables.push({
      table: 'vehicle_assisted',
      rows: records.vehicleAssisted.map((raw) =>
        vehicleAssistedRow(checked(vehicleAssistedDrivingCaptureSchema, raw)),
      ),
    })
  }

  if (records.vehicleCaptures) {
    tables.push({
      table: 'vehicle_captures',
      rows: records.vehicleCaptures.map((raw) =>
        vehicleCaptureRow(checked(vehicleCaptureSchema, raw)),
      ),
    })
  }

  return tables
}

/** A record validated, and kept as written, so the backup reads back byte for byte. */
function checked<T>(schema: { parse(value: unknown): T }, raw: unknown): T {
  schema.parse(raw)

  return raw as T
}

function upsert(table: Table, row: Row) {
  return upsertSql(table, { key: quote(row.key), record: quote(row.record) })
}

function keysOf(rows: readonly Row[]) {
  return rows.map((row) => row.key)
}

/** Marks deleted the rows of a table whose keys aren't in the snapshot. */
function remove(table: Table, keys: string[], now: string) {
  const kept = keys.length > 0 ? ` AND key NOT IN (${keys.map(quote).join(', ')})` : ''

  return `UPDATE ${table} SET deleted_at = ${quote(now)}, rev = ${REVISION}
  WHERE deleted_at IS NULL${kept}`
}

function quote(value: string) {
  return `'${value.replaceAll("'", "''")}'`
}
