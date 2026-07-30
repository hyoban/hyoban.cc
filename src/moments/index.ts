import type { CollectionEntry } from 'astro:content'
import { getCollection } from 'astro:content'
import { getAssetUrl } from '@/asset-url'
import { locations, type LocationId } from '@/data/locations'
import { parseMomentDocument, type CanonicalMoment } from '@/moments/content'
import {
  getLocation,
  groupMomentsByLocation,
  type MapLocation,
} from '@/moments/map-locations'

const calendarDateFormatter = new Intl.DateTimeFormat('en-CA', {
  day: '2-digit',
  month: '2-digit',
  timeZone: 'Asia/Singapore',
  year: 'numeric',
})

const momentDocuments = import.meta.glob<string>(
  '/src/content/moments/**/index.md',
  { eager: true, import: 'default', query: '?raw' },
)

const momentAssetDocuments = import.meta.glob<Record<string, AssetMetadata>>(
  '/src/content/moments/**/assets.json',
  { eager: true, import: 'default' },
)

type MomentEntry = CollectionEntry<'moments'>

type AssetMetadata = {
  bytes: number
  contentType: string
  etag: string
  height?: number
  width?: number
}

type RemoteImage = {
  height: number
  src: string
  width: number
}

export type ResolvedMomentMedia =
  | {
      alt: string
      src: RemoteImage
      type: 'image'
    }
  | {
      alt: string
      contentType: string
      poster?: RemoteImage
      src: string
      type: 'video'
    }

export type Moment = {
  dateKey: string
  id: string
  location?: ResolvedMomentLocation
  locationId?: LocationId
  media: ResolvedMomentMedia[]
  occurredAt: string
  occurredLabel?: string
  order: number
  text: string
}

export type ResolvedMomentLocation = MapLocation & {
  id: LocationId
}

let momentsPromise: Promise<Moment[]> | undefined

export function getMoments() {
  momentsPromise ??= loadMoments()
  return momentsPromise
}

export function getActiveDateKeys(moments: Moment[]) {
  return [...new Set(moments.map(moment => moment.dateKey))]
}

export function getArchiveMonthKeys(moments: Moment[]) {
  const activeDates = getActiveDateKeys(moments)

  if (activeDates.length === 0) {
    return []
  }

  const [startYear, startMonth] = parseMonthKey(activeDates[0]!)
  const [endYear, endMonth] = parseMonthKey(activeDates.at(-1)!)
  const months = []
  let year = startYear
  let month = startMonth

  while (year < endYear || (year === endYear && month <= endMonth)) {
    months.push(`${year}-${String(month).padStart(2, '0')}`)
    month += 1

    if (month === 13) {
      month = 1
      year += 1
    }
  }

  return months
}

export function getMappedLocationGroups(moments: Moment[]) {
  return groupMomentsByLocation(locations, moments)
}

export function getCalendarDateKey(date: Date) {
  const parts = Object.fromEntries(
    calendarDateFormatter
      .formatToParts(date)
      .map(part => [part.type, part.value]),
  ) as Record<'day' | 'month' | 'year', string>

  return `${parts.year}-${parts.month}-${parts.day}`
}

export function getMomentPath(dateKey: string) {
  return `/calendar/${dateKey.replaceAll('-', '/')}`
}

export function getMonthPath(monthKey: string) {
  return `/calendar/${monthKey.replace('-', '/')}`
}

async function loadMoments() {
  const entries = await getCollection('moments', ({ data }) => !data.hidden)

  return entries
    .map(resolveMoment)
    .sort(compareMoments)
}

function resolveMoment(entry: MomentEntry): Moment {
  const source = parseMomentDocument(getMomentDocument(entry), { id: entry.id })
  const dateKey = source.occurredAt.slice(0, 10)
  const order = getMomentOrder(entry.id, dateKey)
  const moment: Moment = {
    dateKey,
    id: entry.id,
    media: source.media.map(item => resolveMedia(entry, item)),
    occurredAt: source.occurredAt,
    order,
    text: source.text,
  }

  if (source.occurredAt.length > 10) {
    moment.occurredLabel = source.occurredAt.slice(11, 16)
  }

  if (source.location) {
    const locationId = source.location as LocationId
    const location = getLocation(locations, locationId)
    moment.locationId = locationId
    moment.location = { id: locationId, ...location }
  }

  return moment
}

function compareMoments(first: Moment, second: Moment) {
  const dateComparison = first.dateKey.localeCompare(second.dateKey)

  if (dateComparison !== 0) {
    return dateComparison
  }

  const firstHasTime = first.occurredAt.length > 10
  const secondHasTime = second.occurredAt.length > 10

  if (firstHasTime && secondHasTime) {
    return Date.parse(first.occurredAt) - Date.parse(second.occurredAt)
      || first.order - second.order
  }

  return first.order - second.order
}

function getMomentOrder(id: string, dateKey: string) {
  const directory = id.split('/').at(-1)
  const match = directory?.match(/^(\d{2})-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*$/)
  const expectedDay = dateKey.slice(8, 10)

  if (!match || match[1] !== expectedDay) {
    throw new Error(`Invalid Moment directory id: ${id}`)
  }

  return Number.parseInt(match[2]!, 10)
}

function getMomentDocument(entry: MomentEntry) {
  const key = `/src/content/moments/${entry.id}/index.md`
  const document = momentDocuments[key]

  if (!document) {
    throw new Error(`Missing moment document: ${key}`)
  }

  return document
}

function resolveMedia(entry: MomentEntry, media: CanonicalMoment['media'][number]): ResolvedMomentMedia {
  const directory = entry.id.replace(/\/index$/, '')
  const key = `moments/${directory}/${media.file}`
  const metadata = getAssetMetadata(entry, media.file)

  if (media.type === 'image') {
    return {
      alt: media.alt,
      src: resolveRemoteImage(key, metadata),
      type: 'image',
    }
  }

  const posterKey = media.poster
    ? `moments/${directory}/${media.poster}`
    : undefined

  return {
    alt: media.alt,
    contentType: metadata.contentType,
    src: getAssetUrl(key),
    type: 'video',
    ...(posterKey
      ? {
          poster: resolveRemoteImage(
            posterKey,
            getAssetMetadata(entry, media.poster!),
          ),
        }
      : {}),
  }
}

function getAssetMetadata(entry: MomentEntry, file: string) {
  const key = `/src/content/moments/${entry.id}/assets.json`
  const assets = momentAssetDocuments[key]
  const metadata = assets?.[file]

  if (!metadata) {
    throw new Error(`Missing Moment asset metadata: ${entry.id}/${file}`)
  }

  return metadata
}

function resolveRemoteImage(key: string, metadata: AssetMetadata): RemoteImage {
  if (
    !metadata.contentType.startsWith('image/')
    || !Number.isInteger(metadata.width)
    || !Number.isInteger(metadata.height)
  ) {
    throw new Error(`Invalid Moment image metadata: ${key}`)
  }

  return {
    height: metadata.height!,
    src: getAssetUrl(key),
    width: metadata.width!,
  }
}

function parseMonthKey(key: string): [number, number] {
  const year = Number.parseInt(key.slice(0, 4), 10)
  const month = Number.parseInt(key.slice(5, 7), 10)

  if (!Number.isInteger(year) || !Number.isInteger(month)) {
    throw new Error(`Invalid month key: ${key}`)
  }

  return [year, month]
}
