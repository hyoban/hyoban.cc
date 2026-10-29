import {
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  routesSchema,
  tripRecordSchema,
} from '@hyoban/data-spec'
import {
  collectionSchema,
  collectionToTrip,
  entrySchema,
  getCollectionKey,
  isInCollection,
  momentToEntry,
  publicTrackSchema,
  tripToCollection,
  type Collection,
  type Entry,
} from '@hyoban/data-spec/entry'
import {
  BUMP_REVISION,
  KEYS,
  REVISION,
  collectionRow,
  entryRow,
  placeRow,
  routeRow,
  trackRow,
  upsertSql,
  type Table,
  type Value,
} from './rows.ts'

// Turns records, as `data/` keeps them, into SQL for the database. A snapshot import
// makes it hold exactly the snapshot: new and changed records are written and records
// missing from it are marked deleted (`pnpm data:import`, to restore the backup). A
// merge only writes the records it is given, for what the scripts publish outside the
// API (`pnpm data:push`). Either way only rows that change take the new revision, so a
// client syncing afterwards gets just the difference. Runs in Node, not in the Worker.
//
// The records come in either shape: Moments and trips, or entries, collections and public
// tracks. Each row is written in both (rows.ts), so importing the moved data/ is what moves
// the database over, and importing the first layout again moves it back.

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

  statements.push(...rows.trips.map((row) => upsert('trips', row)))
  statements.push(
    remove(
      'trips',
      rows.trips.map((row) => String(row.id)),
      now,
    ),
  )
  statements.push(...rows.moments.map((row) => upsert('moments', row)))
  statements.push(
    remove(
      'moments',
      rows.moments.map((row) => String(row.uid)),
      now,
    ),
  )

  if (rows.tracks) {
    statements.push(...rows.tracks.map((row) => upsert('tracks', row)))
    statements.push(
      remove(
        'tracks',
        rows.tracks.map((row) => String(row.uid)),
        now,
      ),
    )
  }

  if (snapshot.routes) {
    routesSchema.parse(snapshot.routes)

    for (const [key, route] of Object.entries(snapshot.routes)) {
      statements.push(upsert('routes', routeRow(key, route)))
    }

    statements.push(remove('routes', Object.keys(snapshot.routes), now))
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
    ...rows.trips.map((row) => upsert('trips', row)),
    ...rows.moments.map((row) => upsert('moments', row)),
    ...(rows.tracks ?? []).map((row) => upsert('tracks', row)),
  ]

  return `${statements.join(';\n')};\n`
}

/** The rows of either shape's records, each row in both shapes. */
function toRows(records: Omit<MergeRecords, 'places'>) {
  const entries: { entry: Entry; record?: unknown; trip?: string | undefined }[] = []
  const collections: { collection: Collection; trip: unknown }[] = []

  // The first shape: Moments as written, trips as written with their waypoints as marks.
  const moments = (records.moments ?? []).map((raw) => ({
    raw,
    moment: momentRecordSchema.parse(raw),
  }))

  for (const { raw, moment } of moments) {
    entries.push({
      entry: entrySchema.parse(momentToEntry(moment)),
      record: raw,
      trip: moment.trip,
    })
  }

  for (const raw of records.trips ?? []) {
    const trip = tripRecordSchema.parse(raw)
    const { collection, marks } = tripToCollection(
      trip,
      moments.map(({ moment }) => moment),
    )

    collections.push({ collection: collectionSchema.parse(collection), trip: raw })
    entries.push(...marks.map((mark) => ({ entry: entrySchema.parse(mark) })))
  }

  // The second shape: the first shape's view of each worked out from it.
  const second = (records.entries ?? []).map((raw) => entrySchema.parse(raw))
  const secondCollections = (records.collections ?? []).map((raw) => collectionSchema.parse(raw))

  for (const collection of secondCollections) {
    collections.push({ collection, trip: collectionToTrip(collection, second) })
  }

  for (const entry of second) {
    const trip = secondCollections.find(
      (collection) => collection.include.includes(entry.uid) && isInCollection(collection, entry),
    )

    entries.push({ entry, ...(trip ? { trip: getCollectionKey(trip) } : {}) })
  }

  return {
    moments: entries.map(({ entry, record, trip }) => entryRow(entry, { record, trip })),
    trips: collections.map(({ collection, trip }) =>
      collectionRow(collection, tripRecordSchema.parse(trip), trip),
    ),
    tracks: records.tracks?.map((raw) => trackRow(publicTrackSchema.parse(raw))),
  }
}

function upsert(table: Table, row: Record<string, Value>) {
  return upsertSql(
    table,
    Object.fromEntries(Object.entries(row).map(([column, value]) => [column, literal(value)])),
  )
}

/** Marks deleted the rows of a table whose keys aren't in the snapshot. */
function remove(table: Table, keys: string[], now: string) {
  const kept = keys.length > 0 ? ` AND ${KEYS[table]} NOT IN (${keys.map(quote).join(', ')})` : ''

  return `UPDATE ${table} SET deleted_at = ${quote(now)}, rev = ${REVISION}
  WHERE deleted_at IS NULL${kept}`
}

function literal(value: Value) {
  return value === null ? 'NULL' : typeof value === 'number' ? String(value) : quote(value)
}

function quote(value: string) {
  return `'${value.replaceAll("'", "''")}'`
}
