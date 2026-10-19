import {
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  tripRecordSchema,
} from '@hyoban/data-spec'
import {
  BUMP_REVISION,
  KEYS,
  REVISION,
  momentRow,
  placeRow,
  tripRow,
  upsertSql,
  type Table,
  type Value,
} from './rows.ts'

// Turns a snapshot of the records, as `data/` keeps them, into SQL that makes the
// database hold exactly that: new and changed records are written, records missing from
// the snapshot are marked deleted, and only rows that change take the new revision, so
// a client syncing afterwards gets just the difference. Runs in Node, by
// `pnpm data:import`, not in the Worker.

export interface ImportSnapshot {
  manifest: unknown
  places: Record<string, unknown>
  trips: unknown[]
  moments: unknown[]
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
    const parsed = momentRecordSchema.parse(moment)

    statements.push(
      upsert('moments', momentRow(parsed, moment)),
      `DELETE FROM moment_places WHERE moment_uid = ${quote(parsed.uid)}`,
      ...parsed.places.map(
        (place, position) =>
          `INSERT INTO moment_places (moment_uid, position, place_id) VALUES (${quote(parsed.uid)}, ${position}, ${quote(place)})`,
      ),
    )

    return parsed.uid
  })

  statements.push(remove('moments', momentUids, now))

  return `${statements.join(';\n')};\n`
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
