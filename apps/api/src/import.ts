import {
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  routesSchema,
  tripRecordSchema,
} from '@hyoban/data-spec'
import {
  BUMP_REVISION,
  KEYS,
  REVISION,
  momentRow,
  placeRow,
  routeRow,
  tripRow,
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

export interface ImportSnapshot {
  manifest: unknown
  places: Record<string, unknown>
  trips: unknown[]
  moments: unknown[]
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

  const tripIds = snapshot.trips.map((trip) => {
    const parsed = tripRecordSchema.parse(trip)

    statements.push(upsert('trips', tripRow(parsed, trip)))

    return parsed.id
  })

  statements.push(remove('trips', tripIds, now))

  const momentUids = snapshot.moments.map((moment) => {
    statements.push(...momentStatements(moment))

    return momentRecordSchema.parse(moment).uid
  })

  statements.push(remove('moments', momentUids, now))

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
  trips: unknown[]
  moments: unknown[]
}

/**
 * Writes these records over whatever the database has for them, leaving every other
 * record alone, and stamps the manifest with `generator` and `now`.
 */
export function buildMergeSql(records: MergeRecords, now: string, generator: string): string {
  placesSchema.parse(records.places)

  const statements = [
    BUMP_REVISION,
    `UPDATE meta SET value = json_set(value, '$.generator', ${quote(generator)}, '$.updatedAt', ${quote(now)}) WHERE key = 'manifest'`,
    ...Object.entries(records.places).map(([id, place]) => upsert('places', placeRow(id, place))),
    ...records.trips.map((trip) => upsert('trips', tripRow(tripRecordSchema.parse(trip), trip))),
    ...records.moments.flatMap((moment) => momentStatements(moment)),
  ]

  return `${statements.join(';\n')};\n`
}

function momentStatements(moment: unknown) {
  return [upsert('moments', momentRow(momentRecordSchema.parse(moment), moment))]
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
