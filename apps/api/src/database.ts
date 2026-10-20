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

interface ChangeRow extends KeyedRow {
  rev: number
  deleted_at: string | null
}

/** A position in the change feed: after this revision, and after this uid within it, up to `upto`. */
export interface Cursor {
  /** The database's revision when the feed's first page was read; no page goes past it. */
  upto: number
  rev: number
  uid: string
  /** Whether the feed is from the start, which leaves out what was removed. */
  live: boolean
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
  db.prepare(`SELECT id AS key, record FROM places WHERE deleted_at IS NULL ORDER BY id`)

export const tripsQuery = (db: Database) =>
  db.prepare(`SELECT record FROM trips WHERE deleted_at IS NULL ORDER BY start_date, id`)

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

// `GET /v1/routes` is worked out from every Moment, so `meta` keeps its trips as the
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

/**
 * What changed after a revision, as JSON text: the Moments a page at a time, oldest
 * change first, and on the first page the places and trips, all of them on a feed from
 * the start. Drafts are left out unless `drafts` is set; what was removed comes back by
 * key in `deleted`, `deletedPlaces` and `deletedTrips`, except on a feed from the start,
 * which has nothing to remove. Every page is of the revision the first was read at, so a
 * Moment never names a place the feed hasn't handed out.
 */
export async function readChanges(
  db: Database,
  query: ({ since: number } | { cursor: Cursor }) & { limit: number; drafts: boolean },
): Promise<string> {
  const { limit, drafts } = query
  const first = 'since' in query
  const since = first ? query.since : 0
  const after = first ? { rev: since + 1, uid: '' } : query.cursor
  const live = first ? since === 0 : query.cursor.live
  const upto = first ? REVISION : String(query.cursor.upto)
  const onlyLive = live ? ' AND deleted_at IS NULL' : ''
  const changed = (table: 'places' | 'trips', key: string) =>
    db
      .prepare(
        `SELECT ${key} AS key, rev, deleted_at, record FROM ${table} WHERE rev > ?1${onlyLive} ORDER BY ${key}`,
      )
      .bind(since)
  const [revision, rows, places, trips] = await readAll(db, [
    revisionQuery(db),
    db
      .prepare(
        `SELECT uid AS key, rev, deleted_at, record FROM moments
         WHERE (rev > ?1 OR (rev = ?1 AND uid > ?2)) AND rev <= ${upto}${onlyLive}${drafts ? '' : ' AND published_id IS NOT NULL'}
         ORDER BY rev, uid
         LIMIT ?3`,
      )
      .bind(after.rev, after.uid, limit + 1),
    ...(first ? [changed('places', 'id'), changed('trips', 'id')] : []),
  ])
  const results = rows as ChangeRow[]
  const page = results.slice(0, limit)
  const last = page.at(-1)
  const of = first ? toRevision(revision) : query.cursor.upto
  const next =
    results.length > limit && last
      ? formatCursor({ upto: of, rev: last.rev, uid: last.key, live })
      : null
  const [livePlaces, gonePlaces] = split((places ?? []) as ChangeRow[])
  const [liveTrips, goneTrips] = split((trips ?? []) as ChangeRow[])
  const [liveMoments, goneMoments] = split(page)

  return `{"revision":${of},"moments":${arrayText(liveMoments)},"deleted":${keysText(goneMoments)},"places":${objectText(livePlaces)},"deletedPlaces":${keysText(gonePlaces)},"trips":${arrayText(liveTrips)},"deletedTrips":${keysText(goneTrips)},"next":${JSON.stringify(next)}}`
}

/** One record as stored, or null. */
async function readOne(db: Database, query: string, key: string) {
  const row = await db.prepare(query).bind(key).first<RecordRow>()

  return row?.record ?? null
}

export function readMoment(db: Database, uid: string, { drafts }: { drafts: boolean }) {
  return readOne(
    db,
    `SELECT record FROM moments
     WHERE uid = ?1 AND deleted_at IS NULL${drafts ? '' : ' AND published_id IS NOT NULL'}`,
    uid,
  )
}

/** `{ revision, places }`, places by id. */
export async function readPlaces(db: Database) {
  const [revision, places] = await readAll(db, [revisionQuery(db), placesQuery(db)])

  return `{"revision":${toRevision(revision)},"places":${objectText(places as KeyedRow[])}}`
}

export function readPlace(db: Database, id: string) {
  return readOne(db, `SELECT record FROM places WHERE id = ?1 AND deleted_at IS NULL`, id)
}

/** `{ revision, trips }`, by start date. */
export async function readTrips(db: Database) {
  const [revision, trips] = await readAll(db, [revisionQuery(db), tripsQuery(db)])

  return `{"revision":${toRevision(revision)},"trips":${arrayText(trips as RecordRow[])}}`
}

export function readTrip(db: Database, id: string) {
  return readOne(db, `SELECT record FROM trips WHERE id = ?1 AND deleted_at IS NULL`, id)
}

/**
 * Everything, drafts included, in the shape `data/` keeps it:
 * `{ revision, manifest, places, trips, moments, routes }`.
 */
export async function readSnapshot(db: Database) {
  const [revision, manifest, places, trips, moments, routes] = await readAll(db, [
    revisionQuery(db),
    db.prepare(`SELECT value FROM meta WHERE key = 'manifest'`),
    placesQuery(db),
    tripsQuery(db),
    db.prepare(`SELECT record FROM moments WHERE deleted_at IS NULL ORDER BY uid`),
    routesQuery(db),
  ])
  const [manifestRow] = manifest as { value: string }[]

  return `{"revision":${toRevision(revision)},"manifest":${manifestRow?.value ?? 'null'},"places":${objectText(places as KeyedRow[])},"trips":${arrayText(trips as RecordRow[])},"moments":${arrayText(moments as RecordRow[])},"routes":${objectText(routes as KeyedRow[])}}`
}

export function formatCursor({ upto, rev, uid, live }: Cursor) {
  return `${upto}.${rev}.${uid}${live ? '.live' : ''}`
}

/** A cursor from `formatCursor`, or null when it isn't one. */
export function parseCursor(value: string): Cursor | null {
  const match = value.match(/^(\d+)\.(\d+)\.([0-9A-HJKMNP-TV-Z]{26})(\.live)?$/)

  return match
    ? { upto: Number(match[1]), rev: Number(match[2]), uid: match[3]!, live: Boolean(match[4]) }
    : null
}

export function parseRecord(row: RecordRow): unknown {
  return JSON.parse(row.record)
}

/** The rows still there, and those marked deleted. */
function split(rows: ChangeRow[]) {
  return [
    rows.filter((row) => row.deleted_at === null),
    rows.filter((row) => row.deleted_at !== null),
  ] as const
}

function arrayText(rows: readonly RecordRow[]) {
  return `[${rows.map((row) => row.record).join(',')}]`
}

function objectText(rows: readonly KeyedRow[]) {
  return `{${rows.map((row) => `${JSON.stringify(row.key)}:${row.record}`).join(',')}}`
}

function keysText(rows: readonly KeyedRow[]) {
  return JSON.stringify(rows.map((row) => row.key))
}
