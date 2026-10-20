import {
  momentRecordSchema,
  placeSchema,
  type MomentMedia,
  type MomentRecord,
  type Places,
} from '@hyoban/data-spec'
import { getUploadKey, type Bucket, type UploadPart } from './bucket.ts'
import {
  carryTripRoutes,
  readAll,
  revisionQuery,
  toRevision,
  type Database,
  type Statement,
} from './database.ts'
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
  /** Whether it changed what the trips are built from, so their routes need working out again. */
  changesRoutes?: boolean
}

/**
 * What `POST /v1/changes` did: the id each place sent is known by, the Moments saved, the
 * uids deleted, and the revision the write took, when it changed anything.
 */
export interface AppliedChanges {
  places: Record<string, string>
  moments: MomentRecord[]
  deleted: string[]
  revision?: number
}

/**
 * Everything the app kept on the phone, applied as one write: the places it made, the
 * Moments it wrote or changed, each published, and the Moments it deleted. The Moments
 * name a place by the id it is known by; a Moment's originals must be uploaded first.
 * All of it is checked before anything is written, then committed in one transaction,
 * so the site has all of it or none. A Moment already gone is left out of `deleted`.
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

  const inputs: MomentRecord[] = []

  for (const value of givenMoments) {
    const input = validate(momentRecordSchema, withoutServerFields(value))

    if (inputs.some((other) => other.uid === input.uid)) {
      throw new HttpError(422, `${input.uid} is sent twice.`)
    }

    inputs.push(input)
  }

  const deletedUids = [...new Set(givenDeleted)]
  const both = deletedUids.find((uid) => inputs.some((input) => input.uid === uid))

  if (both) {
    throw new HttpError(422, `${both} is both saved and deleted.`)
  }

  const { places, stored, taken } = await readWriteState(
    context.db,
    [...inputs.map((input) => input.uid), ...deletedUids],
    inputs.map((input) => input.occurredAt.slice(0, 10)),
  )
  const statements: Statement[] = []
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

  const records = inputs.map((input) => {
    const named = { ...input, places: input.places.map((id) => placeIds[id] ?? id) }
    const missing = [...new Set(named.places)].filter((id) => !places[id])

    if (missing.length > 0) {
      throw new HttpError(422, `Unknown places: ${missing.join(', ')}. Send them too.`)
    }

    const record = withPublished(named, stored.get(input.uid)?.record)

    record.published ??= newPublished(context, record, taken)
    taken.add(record.published.id)

    return record
  })

  const deleted = deletedUids.filter((uid) => {
    const existing = stored.get(uid)

    return existing && !existing.deleted
  })

  statements.push(...deleted.map((uid) => deleteStatement(context, uid)))

  // What a publish copied, removed again when the write doesn't go through.
  const copied: string[] = []
  const missingUploads: string[] = []
  let saved: MomentRecord[]
  let uploads: string[]
  let changesRoutes = deleted.some((uid) => tripView(live(stored.get(uid))) !== null)
  let revision: number | undefined

  try {
    uploads = (
      await settle(
        records.map((record) => publishMedia(context.bucket, record, copied, missingUploads)),
      )
    ).flat()

    if (missingUploads.length > 0) {
      throw new HttpError(422, `Upload the media first: ${missingUploads.join(', ')}.`)
    }

    saved = records.map((record) => validate(momentRecordSchema, record))

    // A Moment sent as it is stored isn't written again, so it takes no new revision.
    for (const record of saved) {
      const prior = stored.get(record.uid)

      if (!prior || prior.deleted || prior.text !== JSON.stringify(record)) {
        statements.push(upsert(context.db, 'moments', momentRow(record)))
        changesRoutes ||= tripView(live(prior)) !== tripView(record)
      }
    }

    if (statements.length > 0) {
      // A write the trips don't see keeps the routes' answer, at the revision it takes.
      revision = await commit(context, [
        ...statements,
        ...(changesRoutes ? [] : [carryTripRoutes(context.db)]),
      ])
    }
  } catch (caught) {
    await deleteUploads(context.bucket, copied).catch((cleanup) =>
      console.error('Removing the copies of a write that failed failed', cleanup),
    )
    throw caught
  }

  await deleteUploads(context.bucket, uploads)

  return {
    body: { places: placeIds, moments: saved, deleted, ...(revision ? { revision } : {}) },
    status: 200,
    changesSite: statements.length > 0,
    changesRoutes,
  }
}

/** A Moment as stored, unless it is marked deleted. */
function live(stored: ReturnType<typeof toStored> | undefined) {
  return stored && !stored.deleted ? stored.record : undefined
}

/**
 * What of a Moment the trips are built from, as text to compare, or null when they don't
 * take it: its day and time, its places and its order, while it is published and shown.
 */
function tripView(record: MomentRecord | undefined) {
  return record?.published && !record.hidden
    ? JSON.stringify([record.occurredAt, record.places, record.published.order])
    : null
}

/**
 * What a change set is checked against, in one read: the places, the Moments it names
 * as stored, removed or not, and the published ids already taken on its days.
 */
async function readWriteState(db: Database, uids: string[], days: string[]) {
  const [places, moments, published] = await readAll(db, [
    db.prepare(`SELECT id, record FROM places WHERE deleted_at IS NULL`),
    db
      .prepare(
        `SELECT uid, record, deleted_at FROM moments
         WHERE uid IN (SELECT value FROM json_each(?1))`,
      )
      .bind(JSON.stringify(uids)),
    db
      .prepare(
        `SELECT published_id FROM moments
         WHERE published_id IS NOT NULL AND day IN (SELECT value FROM json_each(?1))`,
      )
      .bind(JSON.stringify([...new Set(days)])),
  ])

  return {
    places: Object.fromEntries(
      (places as { id: string; record: string }[]).map((row) => [
        row.id,
        placeSchema.parse(JSON.parse(row.record)),
      ]),
    ) as Places,
    stored: new Map((moments as MomentRow[]).map((row) => [row.uid, toStored(row)])),
    taken: new Set((published as { published_id: string }[]).map((row) => row.published_id)),
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
 * clear of the published ids [taken], those stored and those of the same write.
 */
function newPublished(
  context: WriteContext,
  record: MomentRecord,
  taken: ReadonlySet<string>,
): NonNullable<MomentRecord['published']> {
  const dateKey = record.occurredAt.slice(0, 10)
  const day = dateKey.replaceAll('-', '/')
  const ids = new Set([...taken].filter((id) => id.startsWith(`${day}-`)))
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

  const row = await context.db
    .prepare(`SELECT uid, record, deleted_at FROM moments WHERE uid = ?1`)
    .bind(momentUid)
    .first<MomentRow>()

  if (row && toStored(row).record.media.find((item) => item.uid === mediaUid)?.published) {
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

/** An original waiting to publish, or null when it isn't uploaded. */
export function readUpload(
  bucket: Bucket,
  { momentUid, mediaUid, part }: { momentUid: string; mediaUid: string; part: UploadPart },
) {
  return bucket.head(getUploadKey(momentUid, mediaUid, part))
}

/**
 * Publishes a published Moment's unpublished media: copies each original from where it
 * was uploaded to the Moment's directory, named by the slug, and gives the item its
 * published block. Returns the uploads, to delete once the record is saved; each copy's
 * key goes in `copied` as soon as it is made, to remove if the write fails, and each
 * original not uploaded goes in `missing`, so the write can name all of them.
 */
async function publishMedia(
  bucket: Bucket,
  record: MomentRecord,
  copied: string[],
  missing: string[],
) {
  const published = record.published!
  const uploads: string[] = []
  const copy = async (item: MomentMedia, part: UploadPart) => {
    const upload = getUploadKey(record.uid, item.uid, part)
    const source = await bucket.get(upload)

    if (!source) {
      missing.push(`${item.uid} (${part})`)
      return undefined
    }

    const contentType = source.httpMetadata?.contentType ?? ''
    const extension = getExtension(contentType)

    if (!extension) {
      throw new HttpError(422, `The upload ${upload} is of an unknown type.`)
    }

    const key = `moments/${published.id}/${getStem({ media: record.media, published }, item)}.${extension}`
    const stored = await bucket.put(key, source.body, { httpMetadata: { contentType } })

    if (!stored) {
      throw new HttpError(500, `Copying ${upload} failed.`)
    }

    copied.push(key)
    uploads.push(upload)

    return { key, contentType, bytes: stored.size, etag: stored.etag }
  }

  await settle(
    record.media
      .filter((item) => !item.published)
      .map(async (item) => {
        switch (item.type) {
          case 'image': {
            const image = await copy(item, 'image')

            if (image) {
              item.published = {
                ...image,
                width: item.width,
                height: item.height,
                variants: [],
                original: true,
              }
            }
            break
          }
          case 'live-photo': {
            const [image, video] = await settle([copy(item, 'image'), copy(item, 'video')])

            if (image && video) {
              item.published = {
                ...image,
                width: item.width,
                height: item.height,
                variants: [],
                original: true,
                video: { ...video, original: true },
              }
            }
            break
          }
          case 'video': {
            const video = await copy(item, 'video')

            if (video) {
              item.published = { ...video, original: true }
            }
            break
          }
        }
      }),
  )

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

/** The uploads a publish copied, gone; R2 turns down a delete of no keys, as for a Moment with no media. */
async function deleteUploads(bucket: Bucket, uploads: string[]) {
  if (uploads.length > 0) {
    await bucket.delete(uploads)
  }
}

/** Runs a write's statements in one transaction, after bumping the revision; gives the revision it took. */
export async function commit({ db, now }: WriteContext, statements: Statement[]) {
  try {
    const results = await db.batch([
      db.prepare(BUMP_REVISION),
      ...statements,
      db
        .prepare(
          `UPDATE meta SET value = json_set(value, '$.generator', ?1, '$.updatedAt', ?2) WHERE key = 'manifest'`,
        )
        .bind(GENERATOR, now),
      revisionQuery(db),
    ])

    return toRevision(results.at(-1)?.results ?? [])
  } catch (error) {
    if (String(error).includes('UNIQUE constraint failed')) {
      throw new HttpError(409, 'Another write took the same id; try again.')
    }

    throw error
  }
}

/**
 * Every promise's result, as `Promise.all`, but only once all of them have settled: a
 * copy that fails then leaves none still going that its cleanup would miss.
 */
async function settle<const T extends readonly Promise<unknown>[]>(
  promises: T,
): Promise<{ -readonly [K in keyof T]: Awaited<T[K]> }> {
  const results = await Promise.allSettled(promises)
  const failure = results.find((result) => result.status === 'rejected')

  if (failure) {
    throw failure.reason
  }

  return results.map((result) => (result as PromiseFulfilledResult<unknown>).value) as {
    -readonly [K in keyof T]: Awaited<T[K]>
  }
}

export function upsert(db: Database, table: Table, row: Record<string, Value>) {
  return db.prepare(upsertSql(table, placeholders(row))).bind(...Object.values(row))
}

interface MomentRow {
  uid: string
  record: string
  deleted_at: string | null
}

/** A Moment as stored, removed or not, and the text it is stored as. */
function toStored(row: MomentRow) {
  return {
    record: momentRecordSchema.parse(JSON.parse(row.record)),
    text: row.record,
    deleted: row.deleted_at !== null,
  }
}
