import {
  momentRecordSchema,
  placeSchema,
  tripRecordSchema,
  type MomentMedia,
  type MomentRecord,
  type Place,
  type Places,
  type TripRecord,
} from '@hyoban/data-spec'
import { getUploadKey, type Bucket, type UploadPart } from './bucket.ts'
import type { Database, Statement } from './database.ts'
import { HttpError, validate } from './http.ts'
import { getExtension, getPublishedId, getSlug, getStem, matchPlace } from './publish.ts'
import {
  BUMP_REVISION,
  REVISION,
  momentRow,
  placeRow,
  placeholders,
  tripRow,
  upsertSql,
  type Table,
  type Value,
} from './rows.ts'

// The write side: the recording app saves drafts, uploads originals and publishes. Every
// write is one transaction that bumps the revision, so the change feed hands it out once.
// A Moment's published id and every published block are the server's: they're set when
// it publishes, never taken from a request, and never changed afterwards.

export const GENERATOR = 'hyoban.cc api'

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

/** Saves a Moment as the app has it; a published one gets its new media published too. */
export async function saveMoment(
  context: WriteContext,
  uid: string,
  body: unknown,
): Promise<WriteResult<MomentRecord>> {
  const input = validate(momentRecordSchema, withoutServerFields(body))

  if (input.uid !== uid) {
    throw new HttpError(422, `The record's uid is ${input.uid}, not ${uid}.`)
  }

  const existing = await readMomentRow(context.db, uid)
  const prior = existing?.record
  const record: MomentRecord = {
    ...input,
    media: input.media.map((item) => {
      const published = prior?.media.find((candidate) => candidate.uid === item.uid)?.published

      return (published ? { ...item, published } : item) as MomentMedia
    }),
    ...(prior?.published ? { published: prior.published } : {}),
  }

  await assertPlaces(context.db, record.places)

  const uploads = record.published ? await publishMedia(context.bucket, record) : []
  const saved = validate(momentRecordSchema, record)

  await commit(context, momentStatements(context.db, saved))
  await context.bucket.delete(uploads)

  return {
    body: saved,
    status: existing ? 200 : 201,
    changesSite: Boolean(saved.published || prior?.published),
  }
}

/** Gives a draft its published id and publishes its media; a published one only its new media. */
export async function publishMoment(
  context: WriteContext,
  uid: string,
): Promise<WriteResult<MomentRecord>> {
  const existing = await readMomentRow(context.db, uid)

  if (!existing || existing.deleted) {
    throw new HttpError(404, 'Not found.')
  }

  const record = structuredClone(existing.record)

  if (!record.published) {
    const dateKey = record.occurredAt.slice(0, 10)
    const { results } = await context.db
      .prepare(`SELECT published_id FROM moments WHERE day = ?1 AND published_id IS NOT NULL`)
      .bind(dateKey)
      .all<{ published_id: string }>()
    const ids = new Set(results.map((row) => row.published_id))
    const orders = [...ids].map((id) => Number(id.slice(11, 13)))
    const { id, order, slug } = getPublishedId(dateKey, getSlug(record), { ids, orders })

    record.published = { id, slug, order, at: context.now }
  }

  const uploads = await publishMedia(context.bucket, record)
  const saved = validate(momentRecordSchema, record)

  await commit(context, momentStatements(context.db, saved))
  await context.bucket.delete(uploads)

  return { body: saved, status: 200, changesSite: true }
}

export async function deleteMoment(context: WriteContext, uid: string): Promise<WriteResult<null>> {
  const existing = await readMomentRow(context.db, uid)

  if (!existing || existing.deleted) {
    throw new HttpError(404, 'Not found.')
  }

  await commit(context, [
    context.db
      .prepare(`UPDATE moments SET deleted_at = ?1, rev = ${REVISION} WHERE uid = ?2`)
      .bind(context.now, uid),
  ])

  return { body: null, status: 204, changesSite: Boolean(existing.record.published) }
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
 * Adds a place, unless it is one already known: by id, or within 200 m or by name, which
 * the response's id then names. A known place keeps the point and name it has.
 */
export async function savePlace(
  context: WriteContext,
  id: string,
  body: unknown,
): Promise<WriteResult<{ id: string; place: Place }>> {
  const place = validate(placeSchema, body)
  const places = await readPlaceRecords(context.db)
  const match = matchPlace(id, place, places)

  if (match.status !== 'new') {
    return { body: { id: match.id, place: places[match.id]! }, status: 200, changesSite: false }
  }

  await commit(context, [upsert(context.db, 'places', placeRow(id, place))])

  return { body: { id, place }, status: 201, changesSite: true }
}

export async function saveTrip(
  context: WriteContext,
  id: string,
  body: unknown,
): Promise<WriteResult<TripRecord>> {
  const trip = validate(tripRecordSchema, body)

  if (trip.id !== id) {
    throw new HttpError(422, `The trip's id is ${trip.id}, not ${id}.`)
  }

  await assertPlaces(context.db, [
    ...trip.waypoints.map((waypoint) => waypoint.place),
    ...[...trip.straightLegs, ...trip.sameWayBack].flatMap((leg) => [leg.from, leg.to]),
  ])

  const existing = await context.db
    .prepare(`SELECT 1 FROM trips WHERE id = ?1 AND deleted_at IS NULL`)
    .bind(id)
    .first()

  await commit(context, [upsert(context.db, 'trips', tripRow(trip))])

  return { body: trip, status: existing ? 200 : 201, changesSite: true }
}

/**
 * Publishes a published Moment's unpublished media: copies each original from where it
 * was uploaded to the Moment's directory, named by the slug, and gives the item its
 * published block. Returns the uploads, to delete once the record is saved.
 */
async function publishMedia(bucket: Bucket, record: MomentRecord) {
  const published = record.published!
  const pending = record.media.filter((item) => !item.published)
  const missing: string[] = []

  for (const item of pending) {
    for (const part of getParts(item)) {
      if (!(await bucket.head(getUploadKey(record.uid, item.uid, part)))) {
        missing.push(`${item.uid} (${part})`)
      }
    }
  }

  if (missing.length > 0) {
    throw new HttpError(422, `Upload the media first: ${missing.join(', ')}.`)
  }

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
        }
        break
      case 'live-photo':
        item.published = {
          ...(await copy('image')),
          width: item.width,
          height: item.height,
          variants: [],
          video: await copy('video'),
        }
        break
      case 'video':
        item.published = await copy('video')
        break
    }
  }

  return uploads
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

function getParts(item: MomentMedia): UploadPart[] {
  return item.type === 'image' ? ['image'] : item.type === 'video' ? ['video'] : ['image', 'video']
}

/** Runs a write's statements in one transaction, after bumping the revision. */
async function commit({ db, now }: WriteContext, statements: Statement[]) {
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

function upsert(db: Database, table: Table, row: Record<string, Value>) {
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

/** A 422 naming any of these places the database doesn't know. */
async function assertPlaces(db: Database, ids: string[]) {
  if (ids.length === 0) {
    return
  }

  const unique = [...new Set(ids)]
  const { results } = await db
    .prepare(
      `SELECT id FROM places WHERE deleted_at IS NULL AND id IN (${unique.map((_, index) => `?${index + 1}`).join(', ')})`,
    )
    .bind(...unique)
    .all<{ id: string }>()
  const known = new Set(results.map((row) => row.id))
  const missing = unique.filter((id) => !known.has(id))

  if (missing.length > 0) {
    throw new HttpError(422, `Unknown places: ${missing.join(', ')}. Save them first.`)
  }
}
