// The part of D1 the API uses, so the tests can hand it an in-memory SQLite instead.

export interface Database {
  prepare(query: string): Statement
}

export interface Statement {
  bind(...values: unknown[]): Statement
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>
  first<T = Record<string, unknown>>(): Promise<T | null>
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

export async function readRevision(db: Database) {
  const row = await db.prepare(`SELECT value FROM meta WHERE key = 'revision'`).first<{
    value: string
  }>()

  return Number(row?.value ?? 0)
}

/**
 * The Moments changed after `cursor`, oldest change first. Drafts are left out unless
 * `drafts` is set; removed Moments come back as uids in `deleted`.
 */
export async function readMomentChanges(
  db: Database,
  { cursor, drafts, limit }: { cursor: Cursor; drafts: boolean; limit: number },
): Promise<MomentChanges> {
  const revision = await readRevision(db)
  const { results } = await db
    .prepare(
      `SELECT uid, rev, deleted_at, record FROM moments
       WHERE (rev > ?1 OR (rev = ?1 AND uid > ?2))${drafts ? '' : ' AND published_id IS NOT NULL'}
       ORDER BY rev, uid
       LIMIT ?3`,
    )
    .bind(cursor.rev, cursor.uid, limit + 1)
    .all<ChangeRow>()
  const page = results.slice(0, limit)
  const last = page.at(-1)

  return {
    revision,
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

export async function readPlaces(db: Database) {
  const { results } = await db
    .prepare(`SELECT id, record FROM places WHERE deleted_at IS NULL ORDER BY id`)
    .all<RecordRow & { id: string }>()

  return Object.fromEntries(results.map((row) => [row.id, parseRecord(row)]))
}

export async function readPlace(db: Database, id: string) {
  const row = await db
    .prepare(`SELECT record FROM places WHERE id = ?1 AND deleted_at IS NULL`)
    .bind(id)
    .first<RecordRow>()

  return row && parseRecord(row)
}

export async function readTrips(db: Database) {
  const { results } = await db
    .prepare(`SELECT record FROM trips WHERE deleted_at IS NULL ORDER BY start_date, id`)
    .all<RecordRow>()

  return results.map(parseRecord)
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
  const revision = await readRevision(db)
  const manifest = await db
    .prepare(`SELECT value FROM meta WHERE key = 'manifest'`)
    .first<{ value: string }>()
  const { results: moments } = await db
    .prepare(`SELECT record FROM moments WHERE deleted_at IS NULL ORDER BY uid`)
    .all<RecordRow>()

  return {
    revision,
    manifest: manifest ? JSON.parse(manifest.value) : null,
    places: await readPlaces(db),
    trips: await readTrips(db),
    moments: moments.map(parseRecord),
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
