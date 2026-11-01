import { REVISION } from './rows.ts'

// The part of D1 the API uses, so the tests can hand it an in-memory SQLite instead.

export interface Database {
  prepare(query: string): Statement
  /** Runs the statements in one transaction; each SELECT's result carries its rows. */
  batch(statements: Statement[]): Promise<{ results?: unknown[] }[]>
}

export interface Statement {
  bind(...values: unknown[]): Statement
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>
  first<T = Record<string, unknown>>(): Promise<T | null>
  run(): Promise<unknown>
}

// The reads answer with JSON text put together from the records as stored, which are
// JSON already: parsing every record only to write it out again is most of what a read
// of all of them would cost the Worker.

interface RecordRow {
  record: string
}

interface KeyedRow extends RecordRow {
  key: string
}

/**
 * Runs reads in one round trip and one transaction, so what they read is of the same
 * revision, and gives each one's rows.
 */
export async function readAll<const T extends readonly Statement[]>(
  db: Database,
  statements: T,
): Promise<{ [K in keyof T]: unknown[] }> {
  const results = await db.batch([...statements])

  return results.map((result) => result.results ?? []) as { [K in keyof T]: unknown[] }
}

// The reads more than one answer is made of, each with what turns its rows into records.

export const revisionQuery = (db: Database) =>
  db.prepare(`SELECT value FROM meta WHERE key = 'revision'`)

export const placesQuery = (db: Database) =>
  db.prepare(`SELECT key, record FROM places WHERE deleted_at IS NULL ORDER BY key`)

export const routesQuery = (db: Database) =>
  db.prepare(`SELECT key, record FROM routes WHERE deleted_at IS NULL ORDER BY key`)

export function toRevision(rows: unknown[]) {
  return Number((rows as { value: string }[])[0]?.value ?? 0)
}

/** Records by key, as `data/places.json` and `data/routes.json` keep them. */
export function toRecordsByKey(rows: unknown[]): Record<string, unknown> {
  return Object.fromEntries((rows as KeyedRow[]).map((row) => [row.key, parseRecord(row)]))
}

export async function readRevision(db: Database) {
  return toRevision([await revisionQuery(db).first()].filter(Boolean))
}

// `GET /v2/routes` is worked out from every Moment, so `meta` keeps its trips as the
// JSON text they are served as, their ETag, and the revision they are of.

export const TRIP_ROUTES = 'trip_routes'
export const TRIP_ROUTES_ETAG = 'trip_routes_etag'
export const TRIP_ROUTES_REVISION = 'trip_routes_revision'

/**
 * In a write the trips don't see, after the revision is bumped: the routes kept for the
 * revision before are those of this one too, so they aren't worked out again.
 */
export function carryTripRoutes(db: Database) {
  return db
    .prepare(
      `UPDATE meta SET value = CAST(${REVISION} AS TEXT)
       WHERE key = ?1 AND CAST(value AS INTEGER) = ${REVISION} - 1`,
    )
    .bind(TRIP_ROUTES_REVISION)
}

/** One record as stored, or null. */
async function readOne(db: Database, query: string, key: string) {
  const row = await db.prepare(query).bind(key).first<RecordRow>()

  return row?.record ?? null
}

/** `{ revision, places }`, places by id. */
export async function readPlaces(db: Database) {
  const [revision, places] = await readAll(db, [revisionQuery(db), placesQuery(db)])

  return `{"revision":${toRevision(revision)},"places":${objectText(places as KeyedRow[])}}`
}

export function readPlace(db: Database, id: string) {
  return readOne(db, `SELECT record FROM places WHERE key = ?1 AND deleted_at IS NULL`, id)
}

export function parseRecord(row: RecordRow): unknown {
  return JSON.parse(row.record)
}

function objectText(rows: readonly KeyedRow[]) {
  return `{${rows.map((row) => `${JSON.stringify(row.key)}:${row.record}`).join(',')}}`
}
