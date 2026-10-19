import {
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  tripRecordSchema,
} from '@hyoban/data-spec'

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

const REVISION = `(SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'revision')`

export function buildImportSql(snapshot: ImportSnapshot, now: string): string {
  manifestSchema.parse(snapshot.manifest)
  placesSchema.parse(snapshot.places)

  const statements = [
    `UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'`,
    `INSERT INTO meta (key, value) VALUES ('manifest', ${quote(JSON.stringify(snapshot.manifest))})
  ON CONFLICT (key) DO UPDATE SET value = excluded.value`,
  ]

  for (const [id, place] of Object.entries(snapshot.places)) {
    statements.push(
      upsert('places', 'id', {
        id: quote(id),
        rev: REVISION,
        deleted_at: 'NULL',
        record: record(place),
      }),
    )
  }

  statements.push(remove('places', 'id', Object.keys(snapshot.places), now))

  const tripIds = snapshot.trips.map((trip) => {
    const { id, start, end } = tripRecordSchema.parse(trip)

    statements.push(
      upsert('trips', 'id', {
        id: quote(id),
        start_date: quote(start),
        end_date: quote(end),
        rev: REVISION,
        deleted_at: 'NULL',
        record: record(trip),
      }),
    )

    return id
  })

  statements.push(remove('trips', 'id', tripIds, now))

  const momentUids = snapshot.moments.map((moment) => {
    const parsed = momentRecordSchema.parse(moment)

    statements.push(
      upsert('moments', 'uid', {
        uid: quote(parsed.uid),
        occurred_at: quote(parsed.occurredAt),
        day: quote(parsed.occurredAt.slice(0, 10)),
        published_id: parsed.published ? quote(parsed.published.id) : 'NULL',
        trip: parsed.trip ? quote(parsed.trip) : 'NULL',
        hidden: parsed.hidden ? '1' : '0',
        rev: REVISION,
        deleted_at: 'NULL',
        record: record(moment),
      }),
      `DELETE FROM moment_places WHERE moment_uid = ${quote(parsed.uid)}`,
      ...parsed.places.map(
        (place, position) =>
          `INSERT INTO moment_places (moment_uid, position, place_id) VALUES (${quote(parsed.uid)}, ${position}, ${quote(place)})`,
      ),
    )

    return parsed.uid
  })

  statements.push(remove('moments', 'uid', momentUids, now))

  return `${statements.join(';\n')};\n`
}

/** Writes a row, leaving it and its revision alone when nothing in it changed. */
function upsert(table: string, key: string, values: Record<string, string>) {
  const columns = Object.keys(values)
  const updates = columns
    .filter((column) => column !== key)
    .map((column) => `${column} = excluded.${column}`)

  return `INSERT INTO ${table} (${columns.join(', ')}) VALUES (${Object.values(values).join(', ')})
  ON CONFLICT (${key}) DO UPDATE SET ${updates.join(', ')}
  WHERE ${table}.record IS NOT excluded.record OR ${table}.deleted_at IS NOT NULL`
}

/** Marks deleted the rows of a table whose keys aren't in the snapshot. */
function remove(table: string, key: string, keys: string[], now: string) {
  const kept = keys.length > 0 ? ` AND ${key} NOT IN (${keys.map(quote).join(', ')})` : ''

  return `UPDATE ${table} SET deleted_at = ${quote(now)}, rev = ${REVISION}
  WHERE deleted_at IS NULL${kept}`
}

function record(value: unknown) {
  return quote(JSON.stringify(value))
}

function quote(value: string) {
  return `'${value.replaceAll("'", "''")}'`
}
