import {
  momentRecordSchema,
  placeSchema,
  type MomentMedia,
  type MomentRecord,
  type Places,
} from '@hyoban/data-spec'
import { getUploadKey, type Bucket, type UploadPart } from './bucket.ts'
import type { Database, Statement } from './database.ts'
import { HttpError, validate } from './http.ts'
import {
  PROVISIONAL_PLACE_PATTERN,
  getExtension,
  getPlaceId,
  getPublishedId,
  getSlug,
  getStem,
  matchPlace,
} from '@hyoban/data-spec/publish'
import {
  BUMP_REVISION,
  REVISION,
  momentRow,
  placeRow,
  placeholders,
  upsertSql,
  type Table,
  type Value,
} from './rows.ts'

// The write side, as the recording app commits what it kept on the phone: the originals
// uploaded one by one, then everything else as one change set, checked whole and written
// in one transaction that bumps the revision, so the change feed hands it out once. A
// Moment's published id and every published block are the server's: they're set when it
// publishes, never taken from a request, and never changed afterwards.

export const GENERATOR = 'hyoban.cc api'
const PLACE_ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export interface WriteContext {
  db: Database
  bucket: Bucket
  /** When the write happens, as the records stamp it. */
  now: string
}

export interface WriteResult<T> {
  body: T
  status: number
  /** Whether the site shows what changed, so it needs building again. */
  changesSite: boolean
}

/** What `POST /v1/changes` did: the id each place sent is known by, the Moments saved, the uids deleted. */
export interface AppliedChanges {
  places: Record<string, string>
  moments: MomentRecord[]
  deleted: string[]
}

/**
 * Everything the app kept on the phone, applied as one write: the places it made, the
 * Moments it wrote or changed, each published, and the Moments it deleted. A place goes
 * as `PUT /v1/places/:id` takes one, and the Moments name it by the id it is known by;
 * a Moment's originals must be uploaded first. All of it is checked before anything is
 * written, then committed in one transaction, so the site has all of it or none.
 * A Moment already gone is left out of `deleted`.
 */
export async function applyChanges(
  context: WriteContext,
  body: unknown,
): Promise<WriteResult<AppliedChanges>> {
  const sent: Record<string, unknown> = isObject(body) ? body : { moments: null }
  const givenPlaces = sent['places'] ?? {}
  const givenMoments = sent['moments'] ?? []
  const givenDeleted = sent['deleted'] ?? []

  if (
    !isObject(givenPlaces) ||
    !Array.isArray(givenMoments) ||
    !Array.isArray(givenDeleted) ||
    !givenDeleted.every((uid) => typeof uid === 'string')
  ) {
    throw new HttpError(
      422,
      'Send { places, moments, deleted }: places by id, Moment records, and the uids of those deleted.',
    )
  }

  const statements: Statement[] = []
  const places = await readPlaceRecords(context.db)
  const placeIds: Record<string, string> = {}

  for (const [given, value] of Object.entries(givenPlaces)) {
    const provisional = PROVISIONAL_PLACE_PATTERN.test(given)

    if (!provisional && !PLACE_ID.test(given)) {
      throw new HttpError(422, `${given} isn't a place id.`)
    }

    const place = validate(placeSchema, value)
    const match = matchPlace(provisional ? getPlaceId(place.name, places) : given, place, places)

    placeIds[given] = match.id

    if (match.status === 'new') {
      places[match.id] = place
      statements.push(upsert(context.db, 'places', placeRow(match.id, place)))
    }
  }

  // Each Moment checked, its uploads among them, before any is copied or written.
  const claimed = new Set<string>()
  const records: MomentRecord[] = []

  for (const value of givenMoments) {
    const input = validate(momentRecordSchema, withoutServerFields(value))

    if (records.some((record) => record.uid === input.uid)) {
      throw new HttpError(422, `${input.uid} is sent twice.`)
    }

    const named = { ...input, places: input.places.map((id) => placeIds[id] ?? id) }
    const missing = [...new Set(named.places)].filter((id) => !places[id])

    if (missing.length > 0) {
      throw new HttpError(422, `Unknown places: ${missing.join(', ')}. Send them too.`)
    }

    const record = withPublished(named, (await readMomentRow(context.db, input.uid))?.record)

    record.published ??= await newPublished(context, record, claimed)
    claimed.add(record.published.id)
    await assertUploads(context.bucket, record)
    records.push(record)
  }

  const deleted: string[] = []

  for (const uid of new Set(givenDeleted)) {
    if (records.some((record) => record.uid === uid)) {
      throw new HttpError(422, `${uid} is both saved and deleted.`)
    }

    const existing = await readMomentRow(context.db, uid)

    if (existing && !existing.deleted) {
      statements.push(deleteStatement(context, uid))
      deleted.push(uid)
    }
  }

  const uploads: string[] = []
  const saved: MomentRecord[] = []

  for (const record of records) {
    uploads.push(...(await publishMedia(context.bucket, record)))

    const checked = validate(momentRecordSchema, record)

    statements.push(...momentStatements(context.db, checked))
    saved.push(checked)
  }

  if (statements.length > 0) {
    await commit(context, statements)
    await deleteUploads(context.bucket, uploads)
  }

  return {
    body: { places: placeIds, moments: saved, deleted },
    status: 200,
    changesSite: statements.length > 0,
  }
}

/** A Moment as sent, with the published blocks it and its media already have. */
function withPublished(input: MomentRecord, prior: MomentRecord | undefined): MomentRecord {
  return {
    ...input,
    media: input.media.map((item) => {
      const published = prior?.media.find((candidate) => candidate.uid === item.uid)?.published

      return (published ? { ...item, published } : item) as MomentMedia
    }),
    ...(prior?.published ? { published: prior.published } : {}),
  }
}

/**
 * A draft's published block: the next order on its day and an id spelled from its slug,
 * clear of the day's published ids and of those [claimed] by the same write.
 */
async function newPublished(
  context: WriteContext,
  record: MomentRecord,
  claimed: Set<string>,
): Promise<NonNullable<MomentRecord['published']>> {
  const dateKey = record.occurredAt.slice(0, 10)
  const { results } = await context.db
    .prepare(`SELECT published_id FROM moments WHERE day = ?1 AND published_id IS NOT NULL`)
    .bind(dateKey)
    .all<{ published_id: string }>()
  const day = dateKey.replaceAll('-', '/')
  const ids = new Set([
    ...results.map((row) => row.published_id),
    ...[...claimed].filter((id) => id.startsWith(`${day}-`)),
  ])
  const orders = [...ids].map((id) => Number(id.slice(11, 13)))
  const { id, order, slug } = getPublishedId(dateKey, getSlug(record), { ids, orders })

  return { id, slug, order, at: context.now }
}

function deleteStatement(context: WriteContext, uid: string) {
  return context.db
    .prepare(`UPDATE moments SET deleted_at = ?1, rev = ${REVISION} WHERE uid = ?2`)
    .bind(context.now, uid)
}

/** Keeps an original until its Moment publishes. */
export async function uploadMedia(
  context: WriteContext,
  request: Request,
  { momentUid, mediaUid, part }: { momentUid: string; mediaUid: string; part: UploadPart },
): Promise<WriteResult<{ key: string; contentType: string; bytes: number; etag: string }>> {
  const contentType = request.headers.get('Content-Type') ?? ''

  if (!getExtension(contentType) || !contentType.startsWith(`${part}/`)) {
    throw new HttpError(415, `Send the ${part} as one of the ${part} types the API takes.`)
  }

  if (!request.body) {
    throw new HttpError(400, 'The body is empty.')
  }

  const existing = await readMomentRow(context.db, momentUid)

  if (existing?.record.media.find((item) => item.uid === mediaUid)?.published) {
    throw new HttpError(409, 'That media item is already published.')
  }

  const key = getUploadKey(momentUid, mediaUid, part)
  const stored = await context.bucket.put(key, request.body, { httpMetadata: { contentType } })

  if (!stored) {
    throw new HttpError(500, 'The upload was not stored.')
  }

  return {
    body: { key, contentType, bytes: stored.size, etag: stored.etag },
    status: 201,
    changesSite: false,
  }
}

/**
 * Publishes a published Moment's unpublished media: copies each original from where it
 * was uploaded to the Moment's directory, named by the slug, and gives the item its
 * published block. Returns the uploads, to delete once the record is saved.
 */
async function publishMedia(bucket: Bucket, record: MomentRecord) {
  const published = record.published!
  const pending = record.media.filter((item) => !item.published)
  const uploads: string[] = []

  for (const item of pending) {
    const stem = getStem({ media: record.media, published }, item)
    const copy = async (part: UploadPart) => {
      const upload = getUploadKey(record.uid, item.uid, part)
      const source = await bucket.get(upload)
      const contentType = source?.httpMetadata?.contentType ?? ''
      const extension = getExtension(contentType)

      if (!source || !extension) {
        throw new HttpError(422, `The upload ${upload} is gone or of an unknown type.`)
      }

      const key = `moments/${published.id}/${stem}.${extension}`
      const stored = await bucket.put(key, source.body, { httpMetadata: { contentType } })

      if (!stored) {
        throw new HttpError(500, `Copying ${upload} failed.`)
      }

      uploads.push(upload)

      return { key, contentType, bytes: stored.size, etag: stored.etag }
    }

    switch (item.type) {
      case 'image':
        item.published = {
          ...(await copy('image')),
          width: item.width,
          height: item.height,
          variants: [],
          original: true,
        }
        break
      case 'live-photo':
        item.published = {
          ...(await copy('image')),
          width: item.width,
          height: item.height,
          variants: [],
          original: true,
          video: { ...(await copy('video')), original: true },
        }
        break
      case 'video':
        item.published = { ...(await copy('video')), original: true }
        break
    }
  }

  return uploads
}

/** A 422 naming any of a Moment's unpublished media whose originals aren't uploaded. */
async function assertUploads(bucket: Bucket, record: MomentRecord) {
  const missing: string[] = []

  for (const item of record.media.filter((candidate) => !candidate.published)) {
    for (const part of getParts(item)) {
      if (!(await bucket.head(getUploadKey(record.uid, item.uid, part)))) {
        missing.push(`${item.uid} (${part})`)
      }
    }
  }

  if (missing.length > 0) {
    throw new HttpError(422, `Upload the media first: ${missing.join(', ')}.`)
  }
}

/**
 * A Moment as sent, without what only the server sets: its published block, and each
 * media item's published block and phone-only `local`. A copy the app kept from an
 * earlier reply, or one it changed, is then neither checked nor taken.
 */
function withoutServerFields(body: unknown): unknown {
  if (!isObject(body)) {
    return body
  }

  const { published: _published, ...moment } = body

  return Array.isArray(moment['media'])
    ? {
        ...moment,
        media: moment['media'].map((item: unknown) => {
          if (!isObject(item)) {
            return item
          }

          const { published: _itemPublished, local: _local, ...rest } = item

          return rest
        }),
      }
    : moment
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** The uploads a publish copied, gone; R2 turns down a delete of no keys, as for a Moment with no media. */
async function deleteUploads(bucket: Bucket, uploads: string[]) {
  if (uploads.length > 0) {
    await bucket.delete(uploads)
  }
}

function getParts(item: MomentMedia): UploadPart[] {
  return item.type === 'image' ? ['image'] : item.type === 'video' ? ['video'] : ['image', 'video']
}

/** Runs a write's statements in one transaction, after bumping the revision. */
export async function commit({ db, now }: WriteContext, statements: Statement[]) {
  try {
    await db.batch([
      db.prepare(BUMP_REVISION),
      ...statements,
      db
        .prepare(
          `UPDATE meta SET value = json_set(value, '$.generator', ?1, '$.updatedAt', ?2) WHERE key = 'manifest'`,
        )
        .bind(GENERATOR, now),
    ])
  } catch (error) {
    if (String(error).includes('UNIQUE constraint failed')) {
      throw new HttpError(409, 'Another write took the same id; try again.')
    }

    throw error
  }
}

function momentStatements(db: Database, record: MomentRecord) {
  return [
    upsert(db, 'moments', momentRow(record)),
    db.prepare(`DELETE FROM moment_places WHERE moment_uid = ?1`).bind(record.uid),
    ...record.places.map((place, position) =>
      db
        .prepare(`INSERT INTO moment_places (moment_uid, position, place_id) VALUES (?1, ?2, ?3)`)
        .bind(record.uid, position, place),
    ),
  ]
}

export function upsert(db: Database, table: Table, row: Record<string, Value>) {
  return db.prepare(upsertSql(table, placeholders(row))).bind(...Object.values(row))
}

/** A Moment as stored, removed or not. */
async function readMomentRow(db: Database, uid: string) {
  const row = await db
    .prepare(`SELECT record, deleted_at FROM moments WHERE uid = ?1`)
    .bind(uid)
    .first<{ record: string; deleted_at: string | null }>()

  return (
    row && {
      record: momentRecordSchema.parse(JSON.parse(row.record)),
      deleted: row.deleted_at !== null,
    }
  )
}

async function readPlaceRecords(db: Database): Promise<Places> {
  const { results } = await db
    .prepare(`SELECT id, record FROM places WHERE deleted_at IS NULL`)
    .all<{ id: string; record: string }>()

  return Object.fromEntries(
    results.map((row) => [row.id, placeSchema.parse(JSON.parse(row.record))]),
  )
}
