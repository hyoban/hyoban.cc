import { placeSchema, type MomentMedia, type Places } from '@hyoban/data-spec'
import {
  collectionSchema,
  entrySchema,
  getUploadEntry,
  isImportedCharge,
  publicTrackSchema,
  type Collection,
  type Entry,
  type PublicTrack,
} from '@hyoban/data-spec/entry'
import pLimit from 'p-limit'
import {
  carryTripRoutes,
  formatCursor,
  readAll,
  revisionQuery,
  toRevision,
  type Cursor,
  type Database,
  type Statement,
} from './database.ts'
import { HttpError, validate } from './http.ts'
import { REVISION, collectionRow, deleteSql, entryRow, getEntryKind, trackRow } from './rows.ts'
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
  type StoredRow,
  type WriteContext,
  type WriteResult,
} from './writes.ts'

// The second shape's side of the API, `/v2`: entries, collections and public tracks, read
// and written as one change set, in the tables @hyoban/data-spec/tables defines. Every read
// is the owner's and sees everything kept here; the site trims what it shows to what is
// public itself. What stays on the phone (a private bill, the raw footprints) never comes
// here: a private part of a plugin that keeps to the phone is dropped even if it is sent.

interface FeedRow extends StoredRow {
  rev: number
}

/** What a row is to a reader: its record, and whether it is gone. */
export function readRow(row: StoredRow) {
  return { key: row.key, text: row.record, gone: row.deleted_at !== null }
}

const COLUMNS = 'key, rev, deleted_at, record'

/**
 * The car's statistics, all public, as the export and the feed's first page name them: a
 * month, a cumulative assisted-driving observation, and a source capture.
 */
const VEHICLE_TABLES = [
  { table: 'vehicle_months', name: 'vehicleMonths', gone: 'deletedVehicleMonths' },
  { table: 'vehicle_assisted', name: 'vehicleAssisted', gone: 'deletedVehicleAssisted' },
  { table: 'vehicle_captures', name: 'vehicleCaptures', gone: 'deletedVehicleCaptures' },
] as const

/**
 * What changed after a revision, as JSON text: the entries a page at a time, oldest change
 * first, and on the first page the places, collections, tracks and the car's statistics, all
 * of them on a feed from the start. What was removed comes back by key, except on a feed from
 * the start, which has nothing to remove. Every page is of the revision the first was read
 * at, so an entry never names a place the feed hasn't handed out.
 */
export async function readEntryChanges(
  db: Database,
  query: ({ since: number } | { cursor: Cursor }) & { limit: number },
): Promise<string> {
  const { limit } = query
  const first = 'since' in query
  const since = first ? query.since : 0
  const after = first ? { rev: since + 1, uid: '' } : query.cursor
  const live = first ? since === 0 : query.cursor.live
  const upto = first ? REVISION : String(query.cursor.upto)
  const changed = (table: string) =>
    db.prepare(`SELECT ${COLUMNS} FROM ${table} WHERE rev > ?1 ORDER BY key`).bind(since)
  const [revision, rows, places, collections, tracks, ...vehicle] = await readAll(db, [
    revisionQuery(db),
    db
      .prepare(
        `SELECT ${COLUMNS} FROM entries
         WHERE (rev > ?1 OR (rev = ?1 AND key > ?2)) AND rev <= ${upto}
         ORDER BY rev, key
         LIMIT ?3`,
      )
      .bind(after.rev, after.uid, limit + 1),
    ...(first
      ? [
          changed('places'),
          changed('collections'),
          changed('tracks'),
          ...VEHICLE_TABLES.map(({ table }) => changed(table)),
        ]
      : []),
  ])
  const results = rows as FeedRow[]
  const page = results.slice(0, limit)
  const last = page.at(-1)
  const of = first ? toRevision(revision) : query.cursor.upto
  const next =
    results.length > limit && last
      ? formatCursor({ upto: of, rev: last.rev, uid: last.key, live })
      : null
  const read = (rows: unknown[] | undefined) => ((rows ?? []) as StoredRow[]).map(readRow)
  const [liveEntries, goneEntries] = split(page.map(readRow), live)
  const [livePlaces, gonePlaces] = split(read(places), live)
  const [liveCollections, goneCollections] = split(read(collections), live)
  const [liveTracks, goneTracks] = split(read(tracks), live)
  const vehicleText = VEHICLE_TABLES.map(({ name, gone }, index) => {
    const [liveRows, goneRows] = split(read(vehicle[index]), live)

    return `${JSON.stringify(name)}:${arrayText(liveRows)},${JSON.stringify(gone)}:${keysText(goneRows)},`
  }).join('')

  return `{"revision":${of},"entries":${arrayText(liveEntries)},"deleted":${keysText(goneEntries)},"places":${keyedText(livePlaces)},"deletedPlaces":${keysText(gonePlaces)},"collections":${arrayText(liveCollections)},"deletedCollections":${keysText(goneCollections)},"tracks":${arrayText(liveTracks)},"deletedTracks":${keysText(goneTracks)},${first ? vehicleText : ''}"next":${JSON.stringify(next)}}`
}

/** One row's record, or null when there is none or it is deleted. */
async function readOne(db: Database, table: string, key: string) {
  const row = await db
    .prepare(`SELECT ${COLUMNS} FROM ${table} WHERE key = ?1`)
    .bind(key)
    .first<StoredRow>()

  return row && row.deleted_at === null ? row.record : null
}

/** One entry as stored, or null. */
export function readEntry(db: Database, uid: string) {
  return readOne(db, 'entries', uid)
}

/** `{ revision, collections }`, by start date. */
export async function readCollections(db: Database) {
  const [revision, rows] = await readAll(db, [
    revisionQuery(db),
    db.prepare(
      `SELECT ${COLUMNS} FROM collections WHERE deleted_at IS NULL ORDER BY start_day, key`,
    ),
  ])

  return `{"revision":${toRevision(revision)},"collections":${arrayText((rows as StoredRow[]).map(readRow))}}`
}

export function readCollection(db: Database, key: string) {
  return readOne(db, 'collections', key)
}

const tracksQuery = (db: Database) =>
  db.prepare(`SELECT ${COLUMNS} FROM tracks WHERE deleted_at IS NULL ORDER BY start_at, key`)

/** `{ revision, tracks }`: the spans of footprints made public, by when they start. */
export async function readTracks(db: Database) {
  const [revision, rows] = await readAll(db, [revisionQuery(db), tracksQuery(db)])

  return `{"revision":${toRevision(revision)},"tracks":${arrayText((rows as StoredRow[]).map(readRow))}}`
}

/**
 * Everything kept here, drafts included, in the shape `data/` keeps it:
 * `{ revision, manifest, places, entries, collections, tracks, routes }`, and the car's
 * statistics as `vehicleMonths`, `vehicleAssisted` and `vehicleCaptures`.
 */
export async function readEntrySnapshot(db: Database) {
  const live = (table: string, order = 'key') =>
    db.prepare(`SELECT ${COLUMNS} FROM ${table} WHERE deleted_at IS NULL ORDER BY ${order}`)
  const [revision, manifest, places, entries, collections, tracks, routes, ...vehicle] =
    await readAll(db, [
      revisionQuery(db),
      db.prepare(`SELECT value FROM meta WHERE key = 'manifest'`),
      live('places'),
      live('entries'),
      live('collections', 'start_day, key'),
      tracksQuery(db),
      live('routes'),
      ...VEHICLE_TABLES.map(({ table }) => live(table)),
    ])
  const [manifestRow] = manifest as { value: string }[]
  const read = (rows: unknown[] | undefined) => ((rows ?? []) as StoredRow[]).map(readRow)
  const vehicleText = VEHICLE_TABLES.map(
    ({ name }, index) => `,${JSON.stringify(name)}:${arrayText(read(vehicle[index]))}`,
  ).join('')

  return `{"revision":${toRevision(revision)},"manifest":${manifestRow?.value ?? 'null'},"places":${keyedText(read(places))},"entries":${arrayText(read(entries))},"collections":${arrayText(read(collections))},"tracks":${arrayText(read(tracks))},"routes":${keyedText(read(routes))}${vehicleText}}`
}

/**
 * What of an entry the trips' routes are built from, as text to compare: a mark whole, and a
 * Moment's time and place while it is published and not private; nothing of any other.
 */
function routeView(entry: Entry | undefined) {
  const kind = entry && getEntryKind(entry)

  if (kind === 'mark') {
    return JSON.stringify(entry)
  }

  return kind === 'moment' && entry!.published && entry!.visibility !== 'private'
    ? JSON.stringify([entry!.at, entry!.place])
    : null
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
 * deletedCollections, tracks, deletedTracks }`: the places are named first,
 * each entry is published (its media's originals uploaded first) and checked, and all of it is
 * committed in one transaction or none of it is. A collection is known by its slug, else its
 * uid in lowercase.
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
  const imported = inputs.find(isImportedCharge)

  if (imported) {
    throw new HttpError(422, `${imported.uid} is an imported charge; only an import changes it.`)
  }

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
  const importedStored = [...inputs.map((input) => input.uid), ...deletedUids].find((uid) => {
    const stored = state.stored.get(uid)

    return stored && !stored.deleted && isImportedCharge(stored.entry)
  })

  if (importedStored) {
    throw new HttpError(422, `${importedStored} is an imported charge; only an import changes it.`)
  }

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
    statements.push(context.db.prepare(deleteSql('entries')).bind(context.now, uid))
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

    for (const record of saved) {
      const prior = state.stored.get(record.uid)

      if (!prior || prior.deleted || prior.text !== JSON.stringify(record)) {
        statements.push(upsert(context.db, 'entries', entryRow(record)))
        changesRoutes ||= routeView(prior?.deleted ? undefined : prior?.entry) !== routeView(record)
      }
    }

    for (const collection of givenCollections) {
      statements.push(upsert(context.db, 'collections', collectionRow(collection)))
    }

    for (const key of deletedCollections) {
      statements.push(context.db.prepare(deleteSql('collections')).bind(context.now, key))
    }

    for (const track of givenTracks) {
      statements.push(upsert(context.db, 'tracks', trackRow(track)))
      changesRoutes = true
    }

    for (const uid of deletedTracks) {
      statements.push(context.db.prepare(deleteSql('tracks')).bind(context.now, uid))
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

/** What a change set is checked against: the places, and the entries it names. */
async function readEntryWriteState(db: Database, uids: string[]) {
  const [places, entries] = await readAll(db, [
    db.prepare(`SELECT key, record FROM places WHERE deleted_at IS NULL`),
    db
      .prepare(`SELECT ${COLUMNS} FROM entries WHERE key IN (SELECT value FROM json_each(?1))`)
      .bind(JSON.stringify(uids)),
  ])

  return {
    places: Object.fromEntries(
      (places as StoredRow[]).map((row) => [row.key, placeSchema.parse(JSON.parse(row.record))]),
    ) as Places,
    stored: new Map(
      (entries as StoredRow[]).map((row) => [
        row.key,
        { entry: toEntry(row), text: row.record, deleted: row.deleted_at !== null },
      ]),
    ),
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

/** Records by key, as `data/places.json` and `data/routes.json` keep them. */
function keyedText(rows: readonly { key: string; text: string }[]) {
  return `{${rows.map((row) => `${JSON.stringify(row.key)}:${row.text}`).join(',')}}`
}
