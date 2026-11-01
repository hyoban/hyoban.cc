import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import {
  collectionSchema,
  collectionToTrip,
  entrySchema,
  entryToMoment,
  getPublicEntry,
  isInCollection,
  isMomentEntry,
  momentToEntry,
  publicTrackSchema,
  tripToCollection,
  type Collection,
  type Entry,
  type EntryParts,
  type PublicTrack,
} from '@hyoban/data-spec/entry'
import {
  devicesSchema,
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  routesSchema,
  tripRecordSchema,
  type Devices,
  type Manifest,
  type MomentRecord,
  type Places,
  type Routes,
  type TripRecord,
} from '@hyoban/data-spec'

// Reads the recording app's records for the site's build and for the scripts, from the
// repository's `data/` directory or, when the build fetched one, the API's snapshot in
// the same layout (see api-data.ts). Everything is validated on the way in.
//
// The records are entries, collections and public tracks (`entries/`, `collections/`,
// `tracks/`); Moments and trips in the first layout (`moments/`, `trips/`), from before the
// migration or written since by a script that still writes them, are read as entries too.
// What still works on Moments and trips reads them back in that shape.

/** The repository's `data/`, the committed copy, found from the site or the repository's root. */
export const dataRoot = join(findRepositoryRoot(process.cwd()), 'data')

function findRepositoryRoot(directory: string): string {
  if (existsSync(join(directory, 'pnpm-workspace.yaml'))) return directory
  const parent = dirname(directory)
  return parent === directory ? process.cwd() : findRepositoryRoot(parent)
}

/** Set by the build to the directory it wrote the API's snapshot to. */
export const DATA_ROOT_VARIABLE = 'HYOBAN_DATA_ROOT'

/** Where the Moments, places and trips are read from: the API's snapshot, else `data/`. */
export function getDataRoot() {
  return process.env[DATA_ROOT_VARIABLE] ?? dataRoot
}

export function readManifest(root = getDataRoot()): Manifest {
  return manifestSchema.parse(readJson('manifest.json', root))
}

export function readPlaces(root = getDataRoot()): Places {
  return placesSchema.parse(readJson('places.json', root))
}

/**
 * Each trip leg's route by its key, as the API fetched them; data/'s copy when the
 * export came from an API that doesn't keep them yet.
 */
export function readRoutes(root = getDataRoot()): Routes {
  return routesSchema.parse(
    readJson('routes.json', existsSync(join(root, 'routes.json')) ? root : dataRoot),
  )
}

/**
 * The devices the public sees, by id, from the entries of the days they were bought: each a
 * span from that day to the one it sold on.
 */
export function readDevices(root = getDataRoot()): Devices {
  return devicesSchema.parse(
    Object.fromEntries(
      readPublicParts('device', root).map(({ entry, part }) => [
        part.id,
        {
          name: part.name,
          category: part.category,
          bought: { on: entry.at.slice(0, 10), price: part.price },
          ...(part.sold ? { sold: part.sold } : {}),
          idle: part.idle ?? false,
          ...(part.image ? { image: part.image } : {}),
          ...(part.note ? { note: part.note } : {}),
        },
      ]),
    ),
  )
}

/** Every device's id, private ones too, for what checks a record names a known device. */
export function readDeviceIds(root = getDataRoot()): Set<string> {
  return new Set(
    readEntries(root).flatMap(({ record }) =>
      record.parts.device ? [record.parts.device.id] : [],
    ),
  )
}

export type SubscriptionPart = NonNullable<EntryParts['subscription']>

/**
 * The subscriptions and software bought once the public sees, each with the day it was last
 * paid (or first written down), in the order the use page lists them.
 */
export function readSubscriptions(root = getDataRoot()): (SubscriptionPart & { at: string })[] {
  return readPublicParts('subscription', root)
    .sort(
      (first, second) =>
        (first.entry.order ?? 0) - (second.entry.order ?? 0) ||
        first.entry.at.localeCompare(second.entry.at),
    )
    .map(({ entry, part }) => ({ ...part, at: entry.at }))
}

/** The public entries with a part of this plugin, and that part as the public sees it. */
function readPublicParts<K extends 'device' | 'subscription'>(plugin: K, root: string) {
  return readEntries(root).flatMap(({ record }) => {
    const shown = getPublicEntry(record)
    const part = shown?.parts[plugin]

    return shown && part ? [{ entry: shown, part: part as NonNullable<EntryParts[K]> }] : []
  })
}

/** Every entry, with the file it came from: those under `entries/`, and the first layout's as entries. */
export function readEntries(root = getDataRoot()): { file: string; record: Entry }[] {
  const entries = listJsonFiles('entries', root).map((file) => ({
    file,
    record: entrySchema.parse(readJson(file, root)),
  }))
  const seen = new Set(entries.map(({ record }) => record.uid))
  const moments = readFirstMoments(root)

  for (const { file, record } of moments) {
    if (!seen.has(record.uid)) {
      entries.push({ file, record: entrySchema.parse(momentToEntry(record)) })
    }
  }

  for (const trip of readFirstTrips(root)) {
    for (const mark of tripToCollection(
      trip,
      moments.map(({ record }) => record),
    ).marks) {
      entries.push({ file: `trips/${trip.id}.json`, record: entrySchema.parse(mark) })
    }
  }

  return entries
}

/** Every collection: those under `collections/`, and the first layout's trips as collections. */
export function readCollections(root = getDataRoot()): Collection[] {
  const collections = listJsonFiles('collections', root).map((file) =>
    collectionSchema.parse(readJson(file, root)),
  )
  const slugs = new Set(collections.map((collection) => collection.slug))
  const moments = readFirstMoments(root).map(({ record }) => record)

  for (const trip of readFirstTrips(root)) {
    if (!slugs.has(trip.id)) {
      collections.push(collectionSchema.parse(tripToCollection(trip, moments).collection))
    }
  }

  return collections
}

/** The spans of footprints made public, each with its line. */
export function readPublicTracks(root = getDataRoot()): PublicTrack[] {
  return listJsonFiles('tracks', root).map((file) => publicTrackSchema.parse(readJson(file, root)))
}

/** The trips as the first shape has them, from the collections and their marks. */
export function readTripRecords(root = getDataRoot()): TripRecord[] {
  const entries = readEntries(root).map(({ record }) => record)

  return readCollections(root).map((collection) =>
    tripRecordSchema.parse(collectionToTrip(collection, entries)),
  )
}

/**
 * Every Moment with the file it came from, relative to `data/`: the entries with words, as
 * the first shape has them, each naming the trip that includes it from outside its span.
 */
export function readMomentRecords(root = getDataRoot()): { file: string; record: MomentRecord }[] {
  const collections = readCollections(root)

  return readEntries(root)
    .filter(({ record }) => isMomentEntry(record))
    .map(({ file, record }) => {
      const trip = collections.find(
        (collection) =>
          collection.include.includes(record.uid) && isInCollection(collection, record),
      )

      return {
        file,
        record: momentRecordSchema.parse(entryToMoment(record, trip?.slug)),
      }
    })
}

function readFirstMoments(root: string) {
  return listJsonFiles('moments', root).map((file) => ({
    file,
    record: momentRecordSchema.parse(readJson(file, root)),
  }))
}

function readFirstTrips(root: string) {
  return listJsonFiles('trips', root).map((file) => tripRecordSchema.parse(readJson(file, root)))
}

export function readJson(file: string, root = dataRoot): unknown {
  return JSON.parse(readFileSync(join(root, file), 'utf8'))
}

/** The JSON files under a directory of `data/`, as paths relative to `data/`, sorted; none when it's missing. */
export function listJsonFiles(directory: string, root = dataRoot) {
  if (!existsSync(join(root, directory))) {
    return []
  }

  return readdirSync(join(root, directory), { recursive: true })
    .map(String)
    .filter((file) => file.endsWith('.json'))
    .sort()
    .map((file) => `${directory}/${file.split('\\').join('/')}`)
}
