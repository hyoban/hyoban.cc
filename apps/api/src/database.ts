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

interface RecordRow {
  record: string
}

interface ChangeRow extends RecordRow {
  uid: string
  rev: number
  deleted_at: string | null
}

export interface Snapshot {
  revision: number
  manifest: unknown
  places: Record<string, unknown>
  trips: unknown[]
  moments: unknown[]
  /** Each trip leg's route by its key, as `data/routes.json` keeps them. */
  routes: Record<string, unknown>
}

export interface MomentChanges {
  /** The database's revision when the page was read; pass it as `since` next time. */
  revision: number
  moments: unknown[]
  /** Moments removed since, by uid. */
  deleted: string[]
  /** Where the next page starts, or null on the last one. */
  next: string | null
}

/** A position in the change feed: after this revision, and after this uid within it. */
export interface Cursor {
  rev: number
  uid: string
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
  db.prepare(`SELECT id, record FROM places WHERE deleted_at IS NULL ORDER BY id`)

export const tripsQuery = (db: Database) =>
  db.prepare(`SELECT record FROM trips WHERE deleted_at IS NULL ORDER BY start_date, id`)

export const routesQuery = (db: Database) =>
  db.prepare(`SELECT key, record FROM routes WHERE deleted_at IS NULL ORDER BY key`)

export function toRevision(rows: unknown[]) {
  return Number((rows as { value: string }[])[0]?.value ?? 0)
}

/** Places by id, as `data/places.json` keeps them. */
export function toPlaces(rows: unknown[]) {
  return Object.fromEntries(
    (rows as (RecordRow & { id: string })[]).map((row) => [row.id, parseRecord(row)]),
  )
}

export function toRecords(rows: unknown[]) {
  return (rows as RecordRow[]).map(parseRecord)
}

/** Each leg's route by its key, as `data/routes.json` keeps them. */
export function toRoutes(rows: unknown[]) {
  return Object.fromEntries(
    (rows as (RecordRow & { key: string })[]).map((row) => [row.key, parseRecord(row)]),
  )
}

export async function readRevision(db: Database) {
  return toRevision([await revisionQuery(db).first()].filter(Boolean))
}

/**
 * The Moments changed after `cursor`, oldest change first. Drafts are left out unless
 * `drafts` is set; removed Moments come back as uids in `deleted`.
 */
export async function readMomentChanges(
  db: Database,
  { cursor, drafts, limit }: { cursor: Cursor; drafts: boolean; limit: number },
): Promise<MomentChanges> {
  const [revision, rows] = await readAll(db, [
    revisionQuery(db),
    db
      .prepare(
        `SELECT uid, rev, deleted_at, record FROM moments
         WHERE (rev > ?1 OR (rev = ?1 AND uid > ?2))${drafts ? '' : ' AND published_id IS NOT NULL'}
         ORDER BY rev, uid
         LIMIT ?3`,
      )
      .bind(cursor.rev, cursor.uid, limit + 1),
  ])
  const results = rows as ChangeRow[]
  const page = results.slice(0, limit)
  const last = page.at(-1)

  return {
    revision: toRevision(revision),
    moments: page.filter((row) => row.deleted_at === null).map(parseRecord),
    deleted: page.filter((row) => row.deleted_at !== null).map((row) => row.uid),
    next: results.length > limit && last ? formatCursor({ rev: last.rev, uid: last.uid }) : null,
  }
}

export async function readMoment(db: Database, uid: string, { drafts }: { drafts: boolean }) {
  const row = await db
    .prepare(
      `SELECT record FROM moments
       WHERE uid = ?1 AND deleted_at IS NULL${drafts ? '' : ' AND published_id IS NOT NULL'}`,
    )
    .bind(uid)
    .first<RecordRow>()

  return row && parseRecord(row)
}

/** `{ revision, places }`, places by id. */
export async function readPlaces(db: Database) {
  const [revision, places] = await readAll(db, [revisionQuery(db), placesQuery(db)])

  return { revision: toRevision(revision), places: toPlaces(places) }
}

export async function readPlace(db: Database, id: string) {
  const row = await db
    .prepare(`SELECT record FROM places WHERE id = ?1 AND deleted_at IS NULL`)
    .bind(id)
    .first<RecordRow>()

  return row && parseRecord(row)
}

/** `{ revision, trips }`, by start date. */
export async function readTrips(db: Database) {
  const [revision, trips] = await readAll(db, [revisionQuery(db), tripsQuery(db)])

  return { revision: toRevision(revision), trips: toRecords(trips) }
}

export async function readTrip(db: Database, id: string) {
  const row = await db
    .prepare(`SELECT record FROM trips WHERE id = ?1 AND deleted_at IS NULL`)
    .bind(id)
    .first<RecordRow>()

  return row && parseRecord(row)
}

/** Everything, drafts included, in the shape `data/` keeps it. */
export async function readSnapshot(db: Database): Promise<Snapshot> {
  const [revision, manifest, places, trips, moments, routes] = await readAll(db, [
    revisionQuery(db),
    db.prepare(`SELECT value FROM meta WHERE key = 'manifest'`),
    placesQuery(db),
    tripsQuery(db),
    db.prepare(`SELECT record FROM moments WHERE deleted_at IS NULL ORDER BY uid`),
    routesQuery(db),
  ])
  const [manifestRow] = manifest as { value: string }[]

  return {
    revision: toRevision(revision),
    manifest: manifestRow ? JSON.parse(manifestRow.value) : null,
    places: toPlaces(places),
    trips: toRecords(trips),
    moments: toRecords(moments),
    routes: toRoutes(routes),
  }
}

export function formatCursor({ rev, uid }: Cursor) {
  return `${rev}.${uid}`
}

/** A cursor from `formatCursor`, or null when it isn't one. */
export function parseCursor(value: string): Cursor | null {
  const match = value.match(/^(\d+)\.([0-9A-HJKMNP-TV-Z]{26})$/)

  return match ? { rev: Number(match[1]), uid: match[2]! } : null
}

function parseRecord(row: RecordRow): unknown {
  return JSON.parse(row.record)
}
