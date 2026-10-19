import { existsSync } from 'node:fs'
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises'
import { basename, dirname, extname, join } from 'node:path'

import { getDistanceKm } from '@hyoban/trip-map'

import {
  SCHEMA_VERSION,
  getMomentFile,
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  tripRecordSchema,
} from '@hyoban/data-spec'
import { readMomentRecords, readPlaces } from '../../src/data/store.ts'
import { getContentType } from './r2-assets.mjs'

// Publishes a bundle the recording app exported into data/: its places are matched
// against the ones already there, its trips written, and its Moments given a published
// id and their media uploaded, keeping anything already published for the same uid. The
// receipt carries the published records back to the app, with the places it renamed.

export const GENERATOR = 'hyoban.cc data:publish'

/** Two places this close, or with the same name, are one place. */
const NEARBY_KM = 0.2
const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/

/** Reads an unpacked bundle: the records, and the media files by the uid they belong to. */
export async function readBundle(directory) {
  const rawManifest = JSON.parse(await readFile(join(directory, 'manifest.json'), 'utf8'))

  if (rawManifest?.schemaVersion !== SCHEMA_VERSION) {
    throw new Error(
      `The bundle is schema version ${rawManifest?.schemaVersion}; this repository publishes version ${SCHEMA_VERSION}.`,
    )
  }

  const manifest = await parseFile('manifest.json', manifestSchema)

  const places = existsSync(join(directory, 'places.json'))
    ? await parseFile('places.json', placesSchema)
    : {}
  const trips = []

  for (const file of await listFiles('trips', '.json')) {
    trips.push(await parseFile(file, tripRecordSchema))
  }

  const moments = []

  for (const file of await listFiles('moments', '.json')) {
    moments.push({ file, record: await parseFile(file, momentRecordSchema) })
  }

  const media = new Map()

  for (const file of await listFiles('media')) {
    const uid = basename(file, extname(file))

    if (!ULID_PATTERN.test(uid)) {
      throw new Error(`Media file isn't named by a media uid: ${file}`)
    }

    const contentType = getContentType(file)
    const kind = contentType.startsWith('image/') ? 'image' : 'video'
    media.set(uid, { ...media.get(uid), [kind]: join(directory, file) })
  }

  return { directory, manifest, media, moments, places, trips }

  async function parseFile(file, schema) {
    const result = schema.safeParse(JSON.parse(await readFile(join(directory, file), 'utf8')))

    if (!result.success) {
      throw new Error(`Invalid ${file} in the bundle:\n${result.error.message}`)
    }

    return result.data
  }

  async function listFiles(subdirectory, extension = '') {
    const path = join(directory, subdirectory)

    if (!existsSync(path)) {
      return []
    }

    return (await readdir(path, { recursive: true }))
      .map(String)
      .filter((file) => file.endsWith(extension))
      .sort()
      .map((file) => `${subdirectory}/${file.split('\\').join('/')}`)
  }
}

/**
 * Merges the bundle's places into the repository's: a place already there by id stays
 * as it is, one that sits within 200 m of an existing place or shares its name takes
 * that place's id, and the rest are added.
 */
export function planPlaces(bundlePlaces, repositoryPlaces) {
  const places = { ...repositoryPlaces }
  const renamedPlaces = {}
  const conflicts = []

  for (const [id, place] of Object.entries(bundlePlaces)) {
    const existing = places[id]

    if (existing) {
      if (getDistanceKm(existing, place) > NEARBY_KM) {
        conflicts.push(
          `Place ${id} is ${getDistanceKm(existing, place).toFixed(1)} km from where data/places.json has it; the repository's point was kept.`,
        )
      }

      continue
    }

    const match = Object.entries(places).find(
      ([, candidate]) =>
        candidate.name === place.name || getDistanceKm(candidate, place) <= NEARBY_KM,
    )

    if (match) {
      renamedPlaces[id] = match[0]
      continue
    }

    places[id] = place
  }

  return {
    conflicts,
    places: Object.fromEntries(
      Object.entries(places).sort(([first], [second]) => first.localeCompare(second)),
    ),
    renamedPlaces,
  }
}

/**
 * Publishes a bundle into `dataRoot`. `prepareMedia` turns one media item's files into
 * its published block (uploading on the way); `now` stamps what is published.
 */
export async function publishBundle(bundle, { dataRoot, now = timestamp(), prepareMedia }) {
  const { conflicts, places, renamedPlaces } = planPlaces(bundle.places, readPlaces(dataRoot))
  const renamePlace = (id) => renamedPlaces[id] ?? id
  const repositoryMoments = new Map(
    readMomentRecords(dataRoot).map(({ record }) => [record.uid, record]),
  )
  const publishedIds = new Set()
  const ordersByDate = new Map()

  for (const record of repositoryMoments.values()) {
    if (record.published) {
      publishedIds.add(record.published.id)
      noteOrder(record.occurredAt.slice(0, 10), record.published.order)
    }
  }

  const trips = bundle.trips.map((trip) => ({
    ...trip,
    waypoints: trip.waypoints.map((waypoint) => ({
      ...waypoint,
      place: renamePlace(waypoint.place),
    })),
    straightLegs: trip.straightLegs.map((leg) => ({
      ...leg,
      from: renamePlace(leg.from),
      to: renamePlace(leg.to),
    })),
    sameWayBack: trip.sameWayBack.map((leg) => ({
      ...leg,
      from: renamePlace(leg.from),
      to: renamePlace(leg.to),
    })),
  }))
  const moments = []
  const skipped = []
  const blankAlt = []
  const sortedMoments = [...bundle.moments].sort(
    (first, second) =>
      first.record.occurredAt.localeCompare(second.record.occurredAt) ||
      first.file.localeCompare(second.file),
  )

  for (const { file, record: source } of sortedMoments) {
    const existing = repositoryMoments.get(source.uid)
    const record = {
      ...source,
      places: [...new Set(source.places.map(renamePlace))],
      media: source.media.map(({ local: _local, ...item }) => {
        const prior = existing?.media.find((candidate) => candidate.uid === item.uid)
        return prior?.published ? { ...item, published: prior.published } : item
      }),
      ...(existing?.published ? { published: existing.published } : {}),
    }
    const missing = record.media.filter((item) => !item.published && !hasFiles(item))

    if (missing.length > 0) {
      skipped.push(
        `${file}: no file in media/ for ${missing.map((item) => `${item.uid} (${item.type})`).join(', ')}`,
      )
      continue
    }

    if (!record.published) {
      const dateKey = record.occurredAt.slice(0, 10)
      const order = (ordersByDate.get(dateKey)?.at(-1) ?? 0) + 1
      const slug = getSlug(record)
      const prefix = `${dateKey.replaceAll('-', '/').slice(0, 7)}/${dateKey.slice(8, 10)}-${String(order).padStart(2, '0')}-`
      let id = `${prefix}${slug}`

      for (let attempt = 2; publishedIds.has(id); attempt += 1) {
        id = `${prefix}${slug}-${attempt}`
      }

      record.published = { id, slug: id.slice(prefix.length), order, at: now }
      publishedIds.add(id)
      noteOrder(dateKey, order)
    }

    for (const item of record.media) {
      if (item.published) {
        continue
      }

      item.published = await prepareMedia({
        files: bundle.media.get(item.uid),
        item,
        momentId: record.published.id,
        stem: getStem(record, item),
      })
    }

    const result = momentRecordSchema.safeParse(record)

    if (!result.success) {
      throw new Error(`Publishing ${file} made an invalid record:\n${result.error.message}`)
    }

    moments.push(result.data)

    if (!record.hidden) {
      for (const item of record.media) {
        if (item.alt.trim() === '') {
          blankAlt.push(`${record.published.id} ${item.uid}`)
        }
      }
    }
  }

  const touchedPlaces = Object.fromEntries(
    Object.keys(bundle.places)
      .map(renamePlace)
      .sort((first, second) => first.localeCompare(second))
      .map((id) => [id, places[id]]),
  )
  const manifest = { schemaVersion: SCHEMA_VERSION, generator: GENERATOR, updatedAt: now }

  await writeJson(join(dataRoot, 'manifest.json'), manifest)
  await writeJson(join(dataRoot, 'places.json'), places)

  for (const trip of trips) {
    await writeJson(join(dataRoot, 'trips', `${trip.id}.json`), trip)
  }

  for (const record of moments) {
    await writeJson(join(dataRoot, getMomentFile(record)), record)
  }

  return {
    blankAlt,
    conflicts,
    moments,
    receipt: { manifest: { ...manifest, renamedPlaces }, moments, places: touchedPlaces, trips },
    renamedPlaces,
    skipped,
    trips,
  }

  function hasFiles(item) {
    const files = bundle.media.get(item.uid)

    return item.type === 'video'
      ? Boolean(files?.video)
      : item.type === 'live-photo'
        ? Boolean(files?.image && files?.video)
        : Boolean(files?.image)
  }

  function noteOrder(dateKey, order) {
    ordersByDate.set(
      dateKey,
      [...(ordersByDate.get(dateKey) ?? []), order].sort((a, b) => a - b),
    )
  }
}

/** Writes a receipt as a directory in the bundle's layout, ready to zip. */
export async function writeReceipt(receipt, directory) {
  await writeJson(join(directory, 'manifest.json'), receipt.manifest)
  await writeJson(join(directory, 'places.json'), receipt.places)

  for (const trip of receipt.trips) {
    await writeJson(join(directory, 'trips', `${trip.id}.json`), trip)
  }

  for (const record of receipt.moments) {
    await writeJson(join(directory, getMomentFile(record)), record)
  }
}

/** The slug the app proposed, else the first place, else the first words, else `moment`. */
function getSlug(record) {
  const proposed = typeof record.slug === 'string' ? record.slug : ''

  if (/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(proposed)) {
    return proposed
  }

  if (record.places[0]) {
    return record.places[0]
  }

  const words = record.text
    .normalize('NFKD')
    .toLowerCase()
    .replaceAll(/[^a-z0-9]+/g, ' ')
    .trim()
    .split(' ')
    .filter(Boolean)
    .slice(0, 5)

  return words.length > 0 ? words.join('-') : 'moment'
}

/** The file stem of a media item: the slug, numbered among the record's images or videos. */
function getStem(record, item) {
  const isVideo = item.type === 'video'
  const siblings = record.media.filter((candidate) => (candidate.type === 'video') === isVideo)
  const taken = new Set(
    record.media.flatMap((candidate) =>
      candidate.published
        ? [basename(candidate.published.key, extname(candidate.published.key))]
        : [],
    ),
  )
  const base = `${record.published.slug}${isVideo ? '-video' : ''}`

  for (let index = siblings.indexOf(item) + 1; ; index += 1) {
    const stem = index === 1 ? base : `${base}-${index}`

    if (!taken.has(stem)) {
      return stem
    }
  }
}

export function timestamp(date = new Date()) {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

async function writeJson(path, value) {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`)
}
