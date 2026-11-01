import {
  getMediaKey,
  placeSchema,
  type MomentMedia,
  type Place,
  type Places,
} from '@hyoban/data-spec'
import type { Division } from '@hyoban/data-spec/division'
import { entrySchema, type Entry } from '@hyoban/data-spec/entry'
import { type LimitFunction } from 'p-limit'
import { getUploadKey, type Bucket, type UploadPart } from './bucket.ts'
import { revisionQuery, toRevision, type Database, type Statement } from './database.ts'
import { HttpError, validate } from './http.ts'
import {
  PROVISIONAL_PLACE_PATTERN,
  getExtension,
  getPlaceId,
  matchPlace,
} from '@hyoban/data-spec/publish'
import { BUMP_REVISION, placeRow, upsertSql, type Row, type Table } from './rows.ts'

// The write side, as the recording app commits what it kept on the phone: the originals
// uploaded one by one, then everything else as one change set, checked whole and written
// in one transaction that bumps the revision, so the change feed hands it out once. A
// Moment's published block and every media item's are the server's: they're set when it
// publishes, never taken from a request, and never changed afterwards. Nothing in them
// names the Moment, which is its uid, so a published one can move to another day.

export const GENERATOR = 'hyoban.cc api'
const PLACE_ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export interface WriteContext {
  db: Database
  bucket: Bucket
  /** When the write happens, as the records stamp it. */
  now: string
  /** The division a new place sent without one is in; none is looked up without it. */
  locate?: (place: Place) => Promise<Division | undefined>
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
 * The places a change set sends, each by the id it is known by: a known id stays, one near or
 * named as a known place takes that one's, and any other is added, the app's provisional
 * `p-<ulid>` spelled from its name. A known place sent under its own id takes the kind it is
 * sent with, and nothing else. What it writes goes in `statements`; `places` takes the new.
 */
export async function namePlaces(
  context: WriteContext,
  givenPlaces: Record<string, unknown>,
  places: Places,
  statements: Statement[],
) {
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
      const located = await withDivision(context, place)

      places[match.id] = located
      statements.push(upsert(context.db, 'places', placeRow(match.id, located)))
    } else if (match.status === 'known' && place.kind && place.kind !== places[match.id]?.kind) {
      const kinded = withKind(places[match.id]!, place.kind)

      places[match.id] = kinded
      statements.push(upsert(context.db, 'places', placeRow(match.id, kinded)))
    }
  }

  return placeIds
}

/** A place with another kind, the kind where the spec orders it, after the point. */
function withKind(place: Place, kind: NonNullable<Place['kind']>): Place {
  if (place.kind) {
    return { ...place, kind }
  }

  const entries = Object.entries(place)
  const at = entries.findIndex(([key]) => key === 'longitude') + 1

  entries.splice(at, 0, ['kind', kind])

  return Object.fromEntries(entries) as Place
}

/**
 * A new place with the division it is in, placed after its kind as the spec orders it.
 * One that can't be looked up, DataV being out of reach, is kept without; the
 * `places:locate` script fills it in later.
 */
async function withDivision(context: WriteContext, place: Place): Promise<Place> {
  if (place.division || !context.locate) {
    return place
  }

  let division: Division | undefined

  try {
    division = await context.locate(place)
  } catch (caught) {
    console.error(`Locating ${place.name} failed`, caught)
  }

  if (!division) {
    return place
  }

  const entries = Object.entries(place)
  const at = entries.findIndex(([key]) => key === (place.kind ? 'kind' : 'longitude')) + 1

  entries.splice(at, 0, ['division', division])

  return Object.fromEntries(entries) as Place
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
    .prepare(`SELECT key, record, deleted_at FROM entries WHERE key = ?1`)
    .bind(momentUid)
    .first<StoredRow>()

  if (row && toEntry(row).parts.media?.items.find((item) => item.uid === mediaUid)?.published) {
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
 * was uploaded to the Moment's directory, named by its uid (`getMediaKey`), and gives the item its
 * published block. Returns the uploads, to delete once the record is saved; each copy's
 * key goes in `copied` as soon as it is made, to remove if the write fails, and each
 * original not uploaded goes in `missing`, so the write can name all of them. The copies
 * go through `copying`, which keeps how many run at once within the Worker's connections.
 */
export async function publishMedia(
  bucket: Bucket,
  record: { uid: string; media: MomentMedia[] },
  copied: string[],
  missing: string[],
  copying: LimitFunction,
) {
  const uploads: string[] = []
  const copy = (item: MomentMedia, part: UploadPart) => copying(() => copyOne(item, part))
  const copyOne = async (item: MomentMedia, part: UploadPart) => {
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

    // A live photo's video sits beside its still; anything else is the item itself.
    const key = getMediaKey(
      record.uid,
      item.uid,
      extension,
      item.type === 'live-photo' && part === 'video' ? 'video' : undefined,
    )
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

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** The uploads a publish copied, gone; R2 turns down a delete of no keys, as for a Moment with no media. */
export async function deleteUploads(bucket: Bucket, uploads: string[]) {
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
 * How many originals a write copies at once. A Worker keeps six connections open at a time
 * and holds back the rest; a copy waiting on its write while its read stays open loses the
 * read, so eight copies at once, a Moment of four live photos, fail with "Network
 * connection lost".
 */
export const COPIES_AT_ONCE = 2

/**
 * Every promise's result, as `Promise.all`, but only once all of them have settled: a
 * copy that fails then leaves none still going that its cleanup would miss.
 */
export async function settle<const T extends readonly Promise<unknown>[]>(
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

export function upsert(db: Database, table: Table, row: Row) {
  return db.prepare(upsertSql(table, { key: '?1', record: '?2' })).bind(row.key, row.record)
}

/** A row as stored, deleted or not. */
export interface StoredRow {
  key: string
  record: string
  deleted_at: string | null
}

/** An entries row's entry. */
export function toEntry(row: Pick<StoredRow, 'record'>): Entry {
  return entrySchema.parse(JSON.parse(row.record))
}
