import { placeSchema, type MomentMedia, type Places } from '@hyoban/data-spec'
import {
  collectionSchema,
  collectionToTrip,
  entrySchema,
  getCollectionKey,
  getPublicEntry,
  getUploadEntry,
  isInCollection,
  isMarkEntry,
  publicTrackSchema,
  tripToCollection,
  type Collection,
  type Entry,
  type PublicTrack,
} from '@hyoban/data-spec/entry'
import { tripRecordSchema } from '@hyoban/data-spec'
import pLimit from 'p-limit'
import {
  carryTripRoutes,
  formatCursor,
  readAll,
  revisionQuery,
  routesQuery,
  toRevision,
  type Cursor,
  type Database,
  type Statement,
} from './database.ts'
import { HttpError, validate } from './http.ts'
import { REVISION, collectionRow, entryRow, getEntryKind, trackRow } from './rows.ts'
import {
  COPIES_AT_ONCE,
  commit,
  deleteUploads,
  isObject,
  namePlaces,
  publishMedia,
  settle,
  toEntry,
  upsert,
  type MomentRow,
  type WriteContext,
  type WriteResult,
} from './writes.ts'

// The second shape's side of the API, `/v2`: entries, collections and public tracks, read
// and written as one change set like the first. Every row holds both shapes (rows.ts), so
// `/v1` keeps serving the app installed before this. The public reads see only what is
// public, as `getPublicEntry` trims it: no private entry or part, a bill by its amount,
// currency, category and note alone; the owner sees everything kept here. What stays on the
// phone (a private bill, the raw footprints) never comes here: a private part of a plugin
// that keeps to the phone is dropped even if it is sent.

interface EntryRow extends MomentRow {
  rev: number
  published: number
  shown: string | null
}

interface CollectionRow {
  id: string
  rev: number
  deleted_at: string | null
  record: string
  collection: string | null
  shown: string | null
}

interface TrackRow {
  uid: string
  rev: number
  deleted_at: string | null
  record: string
}

/** What an entry row is to a reader: the entry, what of it is public, and whether it is gone. */
function readEntryRow(row: EntryRow, owner: boolean) {
  const entry = toEntry(row)
  const text = owner
    ? (row.entry ?? JSON.stringify(entry))
    : row.entry
      ? row.shown
      : jsonOrNull(getPublicEntry(entry))
  const gone = row.deleted_at !== null || text === null || (!owner && row.published !== 1)

  return { key: row.uid, text, gone }
}

/** A collection row as a reader sees it, the trip it was worked out from before it had one. */
function readCollectionRow(row: CollectionRow, owner: boolean) {
  const collection = row.collection
    ? row.collection
    : JSON.stringify(
        collectionSchema.parse(
          tripToCollection(tripRecordSchema.parse(JSON.parse(row.record))).collection,
        ),
      )
  const text = owner
    ? collection
    : row.collection
      ? row.shown
      : (JSON.parse(collection) as Collection).visibility === 'private'
        ? null
        : collection

  return { key: row.id, text, gone: row.deleted_at !== null || text === null }
}

function jsonOrNull(value: unknown) {
  return value === undefined ? null : JSON.stringify(value)
}

const ENTRY_COLUMNS = 'uid, kind, rev, deleted_at, published, record, entry, shown'
const COLLECTION_COLUMNS = 'id, rev, deleted_at, record, collection, shown'

/**
 * What changed after a revision, as `/v1/changes` but in the second shape: the entries a page
 * at a time, and on the first page the places, collections and tracks. For the public, an
 * entry that stopped being public is in `deleted`, as one that was deleted.
 */
export async function readEntryChanges(
  db: Database,
  query: ({ since: number } | { cursor: Cursor }) & { limit: number; owner: boolean },
): Promise<string> {
  const { limit, owner } = query
  const first = 'since' in query
  const since = first ? query.since : 0
  const after = first ? { rev: since + 1, uid: '' } : query.cursor
  const live = first ? since === 0 : query.cursor.live
  const upto = first ? REVISION : String(query.cursor.upto)
  const changed = (table: string, key: string, columns: string) =>
    db.prepare(`SELECT ${columns} FROM ${table} WHERE rev > ?1 ORDER BY ${key}`).bind(since)
  const [revision, rows, places, collections, tracks] = await readAll(db, [
    revisionQuery(db),
    db
      .prepare(
        `SELECT ${ENTRY_COLUMNS} FROM moments
         WHERE (rev > ?1 OR (rev = ?1 AND uid > ?2)) AND rev <= ${upto}
         ORDER BY rev, uid
         LIMIT ?3`,
      )
      .bind(after.rev, after.uid, limit + 1),
    ...(first
      ? [
          changed('places', 'id', 'id AS key, rev, deleted_at, record'),
          changed('trips', 'id', COLLECTION_COLUMNS),
          changed('tracks', 'uid', 'uid, rev, deleted_at, record'),
        ]
      : []),
  ])
  const results = rows as EntryRow[]
  const page = results.slice(0, limit)
  const last = page.at(-1)
  const of = first ? toRevision(revision) : query.cursor.upto
  const next =
    results.length > limit && last
      ? formatCursor({ upto: of, rev: last.rev, uid: last.uid, live })
      : null
  const entries = page.map((row) => readEntryRow(row, owner))
  const placeRows = (
    (places ?? []) as { key: string; deleted_at: string | null; record: string }[]
  ).map((row) => ({ key: row.key, text: row.record, gone: row.deleted_at !== null }))
  const collectionRows = ((collections ?? []) as CollectionRow[]).map((row) =>
    readCollectionRow(row, owner),
  )
  const trackRows = ((tracks ?? []) as TrackRow[]).map((row) => ({
    key: row.uid,
    text: row.record,
    gone: row.deleted_at !== null,
  }))
  const [liveEntries, goneEntries] = split(entries, live)
  const [livePlaces, gonePlaces] = split(placeRows, live)
  const [liveCollections, goneCollections] = split(collectionRows, live)
  const [liveTracks, goneTracks] = split(trackRows, live)

  return `{"revision":${of},"entries":${arrayText(liveEntries)},"deleted":${keysText(goneEntries)},"places":{${livePlaces.map((row) => `${JSON.stringify(row.key)}:${row.text}`).join(',')}},"deletedPlaces":${keysText(gonePlaces)},"collections":${arrayText(liveCollections)},"deletedCollections":${keysText(goneCollections)},"tracks":${arrayText(liveTracks)},"deletedTracks":${keysText(goneTracks)},"next":${JSON.stringify(next)}}`
}

/** One entry as its reader may see it, or null. */
export async function readEntry(db: Database, uid: string, owner: boolean) {
  const row = await db
    .prepare(`SELECT ${ENTRY_COLUMNS} FROM moments WHERE uid = ?1`)
    .bind(uid)
    .first<EntryRow>()
  const read = row && readEntryRow(row, owner)

  return read && !read.gone ? read.text : null
}

/** `{ revision, collections }`, by start date. */
export async function readCollections(db: Database, owner: boolean) {
  const [revision, rows] = await readAll(db, [
    revisionQuery(db),
    db.prepare(
      `SELECT ${COLLECTION_COLUMNS} FROM trips WHERE deleted_at IS NULL ORDER BY start_date, id`,
    ),
  ])
  const live = (rows as CollectionRow[])
    .map((row) => readCollectionRow(row, owner))
    .filter((row) => !row.gone)

  return `{"revision":${toRevision(revision)},"collections":${arrayText(live)}}`
}

export async function readCollection(db: Database, key: string, owner: boolean) {
  const row = await db
    .prepare(`SELECT ${COLLECTION_COLUMNS} FROM trips WHERE id = ?1`)
    .bind(key)
    .first<CollectionRow>()
  const read = row && readCollectionRow(row, owner)

  return read && !read.gone ? read.text : null
}

/** `{ revision, tracks }`: the spans of footprints made public, by when they start. */
export async function readTracks(db: Database) {
  const [revision, rows] = await readAll(db, [
    revisionQuery(db),
    db.prepare(`SELECT record FROM tracks WHERE deleted_at IS NULL ORDER BY start_at, uid`),
  ])

  return `{"revision":${toRevision(revision)},"tracks":${arrayText((rows as { record: string }[]).map((row) => ({ text: row.record })))}}`
}

/**
 * Everything kept here, drafts included, in the shape `data/` keeps it:
 * `{ revision, manifest, places, entries, collections, tracks, routes }`.
 */
export async function readEntrySnapshot(db: Database) {
  const [revision, manifest, places, entries, collections, tracks, routes] = await readAll(db, [
    revisionQuery(db),
    db.prepare(`SELECT value FROM meta WHERE key = 'manifest'`),
    db.prepare(`SELECT id AS key, record FROM places WHERE deleted_at IS NULL ORDER BY id`),
    db.prepare(`SELECT ${ENTRY_COLUMNS} FROM moments WHERE deleted_at IS NULL ORDER BY uid`),
    db.prepare(
      `SELECT ${COLLECTION_COLUMNS} FROM trips WHERE deleted_at IS NULL ORDER BY start_date, id`,
    ),
    db.prepare(`SELECT record FROM tracks WHERE deleted_at IS NULL ORDER BY start_at, uid`),
    routesQuery(db),
  ])
  const [manifestRow] = manifest as { value: string }[]
  const keyed = (rows: unknown[]) =>
    `{${(rows as { key: string; record: string }[]).map((row) => `${JSON.stringify(row.key)}:${row.record}`).join(',')}}`

  return `{"revision":${toRevision(revision)},"manifest":${manifestRow?.value ?? 'null'},"places":${keyed(places)},"entries":${arrayText((entries as EntryRow[]).map((row) => readEntryRow(row, true)))},"collections":${arrayText((collections as CollectionRow[]).map((row) => readCollectionRow(row, true)))},"tracks":${arrayText((tracks as { record: string }[]).map((row) => ({ text: row.record })))},"routes":${keyed(routes)}}`
}

/** What `POST /v2/changes` did. */
export interface AppliedEntryChanges {
  places: Record<string, string>
  entries: Entry[]
  deleted: string[]
  collections: Collection[]
  deletedCollections: string[]
  tracks: PublicTrack[]
  deletedTracks: string[]
  revision?: number
}

/**
 * A change set in the second shape, as one write: `{ places, entries, deleted, collections,
 * deletedCollections, tracks, deletedTracks }`. As `/v1/changes`: the places are named first,
 * each entry is published (its media's originals uploaded first) and checked, and all of it is
 * committed in one transaction or none of it is. A collection is known by its slug, else its
 * uid in lowercase; the trips the first shape reads are worked out again whenever a collection
 * or a mark changed.
 */
export async function applyEntryChanges(
  context: WriteContext,
  body: unknown,
): Promise<WriteResult<AppliedEntryChanges>> {
  const sent: Record<string, unknown> = isObject(body) ? body : { entries: null }
  const list = (name: string) => {
    const value = sent[name] ?? []

    if (!Array.isArray(value)) {
      throw new HttpError(
        422,
        'Send { places, entries, deleted, collections, deletedCollections, tracks, deletedTracks }.',
      )
    }

    return value as unknown[]
  }
  const givenPlaces = sent['places'] ?? {}

  if (!isObject(givenPlaces)) {
    throw new HttpError(422, 'Send the places by id.')
  }

  const strings = (name: string) => {
    const values = list(name)

    if (!values.every((value) => typeof value === 'string')) {
      throw new HttpError(422, `${name} lists keys.`)
    }

    return [...new Set(values as string[])]
  }
  const inputs: Entry[] = []

  for (const value of list('entries')) {
    const input = getUploadEntry(validate(entrySchema, withoutServerFields(value)))

    if (inputs.some((other) => other.uid === input.uid)) {
      throw new HttpError(422, `${input.uid} is sent twice.`)
    }

    inputs.push(input)
  }

  const deletedUids = strings('deleted')
  const both = deletedUids.find((uid) => inputs.some((input) => input.uid === uid))

  if (both) {
    throw new HttpError(422, `${both} is both saved and deleted.`)
  }

  const givenCollections = list('collections').map((value) => validate(collectionSchema, value))
  const deletedCollections = strings('deletedCollections')
  const givenTracks = list('tracks').map((value) => validate(publicTrackSchema, value))
  const deletedTracks = strings('deletedTracks')
  const state = await readEntryWriteState(context.db, [
    ...inputs.map((input) => input.uid),
    ...deletedUids,
  ])
  const statements: Statement[] = []
  const placeIds = await namePlaces(context, givenPlaces, state.places, statements)
  const records = inputs.map((input) => {
    const named = input.place ? { ...input, place: placeIds[input.place] ?? input.place } : input

    if (named.place && !state.places[named.place]) {
      throw new HttpError(422, `Unknown place: ${named.place}. Send it too.`)
    }

    const record = withPublished(named, state.stored.get(input.uid))

    record.published ??= { at: context.now }

    return record
  })
  const deleted = deletedUids.filter(
    (uid) => state.stored.has(uid) && !state.stored.get(uid)!.deleted,
  )
  const copied: string[] = []
  const missingUploads: string[] = []
  const copying = pLimit(COPIES_AT_ONCE)
  let uploads: string[]
  let saved: Entry[]
  let revision: number | undefined
  let changesRoutes =
    deleted.length > 0 || givenCollections.length > 0 || deletedCollections.length > 0

  for (const uid of deleted) {
    statements.push(
      context.db
        .prepare(`UPDATE moments SET deleted_at = ?1, rev = ${REVISION} WHERE uid = ?2`)
        .bind(context.now, uid),
    )
  }

  try {
    uploads = (
      await settle(
        records.map((record) =>
          publishMedia(
            context.bucket,
            { uid: record.uid, media: (record.parts.media?.items ?? []) as MomentMedia[] },
            copied,
            missingUploads,
            copying,
          ),
        ),
      )
    ).flat()

    if (missingUploads.length > 0) {
      throw new HttpError(422, `Upload the media first: ${missingUploads.join(', ')}.`)
    }

    saved = records.map((record) => validate(entrySchema, record))

    const collections = new Map(
      state.collections.map((collection) => [getCollectionKey(collection), collection]),
    )

    for (const collection of givenCollections) {
      collections.set(getCollectionKey(collection), collection)
    }

    for (const key of deletedCollections) {
      collections.delete(key)
    }

    const tripOf = (entry: Entry) =>
      [...collections.values()].find(
        (collection) => collection.include.includes(entry.uid) && isInCollection(collection, entry),
      )

    for (const record of saved) {
      const prior = state.stored.get(record.uid)

      if (!prior || prior.deleted || prior.text !== JSON.stringify(record)) {
        const trip = tripOf(record)

        statements.push(
          upsert(
            context.db,
            'moments',
            entryRow(record, trip ? { trip: getCollectionKey(trip) } : {}),
          ),
        )
        changesRoutes ||=
          getEntryKind(record) !== 'entry' ||
          prior?.entry.place !== record.place ||
          prior?.entry.at !== record.at
      }
    }

    // The trips the first shape reads, worked out again from the marks as they now are.
    if (
      givenCollections.length > 0 ||
      saved.some(isMarkEntry) ||
      deleted.some((uid) => isMarkEntry(state.stored.get(uid)!.entry))
    ) {
      const savedUids = new Set(saved.map((entry) => entry.uid))
      const marks = [
        ...state.marks.filter((mark) => !savedUids.has(mark.uid) && !deleted.includes(mark.uid)),
        ...saved.filter(isMarkEntry),
      ]

      for (const collection of collections.values()) {
        statements.push(
          upsert(
            context.db,
            'trips',
            collectionRow(collection, tripRecordSchema.parse(collectionToTrip(collection, marks))),
          ),
        )
      }
    }

    for (const key of deletedCollections) {
      statements.push(
        context.db
          .prepare(
            `UPDATE trips SET deleted_at = ?1, rev = ${REVISION} WHERE id = ?2 AND deleted_at IS NULL`,
          )
          .bind(context.now, key),
      )
    }

    for (const track of givenTracks) {
      statements.push(upsert(context.db, 'tracks', trackRow(track)))
      changesRoutes = true
    }

    for (const uid of deletedTracks) {
      statements.push(
        context.db
          .prepare(
            `UPDATE tracks SET deleted_at = ?1, rev = ${REVISION} WHERE uid = ?2 AND deleted_at IS NULL`,
          )
          .bind(context.now, uid),
      )
    }

    if (statements.length > 0) {
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
    body: {
      places: placeIds,
      entries: saved,
      deleted,
      collections: givenCollections,
      deletedCollections,
      tracks: givenTracks,
      deletedTracks,
      ...(revision ? { revision } : {}),
    },
    status: 200,
    changesSite: statements.length > 0,
    changesRoutes,
  }
}

/** What a change set is checked against: the places, the entries it names, the collections and the marks. */
async function readEntryWriteState(db: Database, uids: string[]) {
  const [places, entries, collections, marks] = await readAll(db, [
    db.prepare(`SELECT id, record FROM places WHERE deleted_at IS NULL`),
    db
      .prepare(
        `SELECT uid, kind, record, entry, deleted_at FROM moments
         WHERE uid IN (SELECT value FROM json_each(?1))`,
      )
      .bind(JSON.stringify(uids)),
    db.prepare(`SELECT ${COLLECTION_COLUMNS} FROM trips WHERE deleted_at IS NULL`),
    db.prepare(
      `SELECT uid, kind, record, entry, deleted_at FROM moments WHERE kind = 'mark' AND deleted_at IS NULL`,
    ),
  ])

  return {
    places: Object.fromEntries(
      (places as { id: string; record: string }[]).map((row) => [
        row.id,
        placeSchema.parse(JSON.parse(row.record)),
      ]),
    ) as Places,
    stored: new Map(
      (entries as MomentRow[]).map((row) => {
        const entry = toEntry(row)

        return [
          row.uid,
          { entry, text: row.entry ?? JSON.stringify(entry), deleted: row.deleted_at !== null },
        ]
      }),
    ),
    collections: (collections as CollectionRow[]).map(
      (row) => JSON.parse(readCollectionRow(row, true).text!) as Collection,
    ),
    marks: (marks as MomentRow[]).map((row) => toEntry(row)),
  }
}

/** An entry as sent, with the published blocks it and its media already have. */
function withPublished(input: Entry, prior: { entry: Entry } | undefined): Entry {
  const media = input.parts.media

  return {
    ...input,
    parts: {
      ...input.parts,
      ...(media
        ? {
            media: {
              ...media,
              items: media.items.map((item) => {
                const published = prior?.entry.parts.media?.items.find(
                  (candidate) => candidate.uid === item.uid,
                )?.published

                return published ? ({ ...item, published } as typeof item) : item
              }),
            },
          }
        : {}),
    },
    ...(prior?.entry.published ? { published: prior.entry.published } : {}),
  }
}

/** An entry as sent, without what only the server sets: its published block, and its media's published blocks and `local`. */
function withoutServerFields(body: unknown): unknown {
  if (!isObject(body)) {
    return body
  }

  const { published: _published, ...entry } = body
  const parts = entry['parts']

  if (!isObject(parts) || !isObject(parts['media']) || !Array.isArray(parts['media']['items'])) {
    return entry
  }

  return {
    ...entry,
    parts: {
      ...parts,
      media: {
        ...parts['media'],
        items: parts['media']['items'].map((item: unknown) => {
          if (!isObject(item)) {
            return item
          }

          const { published: _itemPublished, local: _local, ...rest } = item

          return rest
        }),
      },
    },
  }
}

/** The rows still there, and those gone; a feed from the start leaves the gone out. */
function split<T extends { gone: boolean }>(rows: T[], live: boolean) {
  return [rows.filter((row) => !row.gone), live ? [] : rows.filter((row) => row.gone)] as const
}

function arrayText(rows: readonly { text: string | null }[]) {
  return `[${rows.map((row) => row.text).join(',')}]`
}

function keysText(rows: readonly { key: string }[]) {
  return JSON.stringify(rows.map((row) => row.key))
}
