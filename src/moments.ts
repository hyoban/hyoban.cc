import type { CollectionEntry } from 'astro:content'
import { getCollection } from 'astro:content'
import { getAssetUrl } from '@/asset-url'
import momentMediaMetadataJson from '@/data/moment-media.generated.json'
import { locations, type LocationId } from '@/data/locations'
import { parseMomentDocument, type CanonicalMoment } from '@/moment-content'
import {
  getLocation,
  groupMomentsByLocation,
  type MapLocation,
} from '@/map-locations'

const TIME_ZONE = 'Asia/Singapore'

const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  day: '2-digit',
  month: '2-digit',
  timeZone: TIME_ZONE,
  year: 'numeric',
})

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  hourCycle: 'h23',
  minute: '2-digit',
  timeZone: TIME_ZONE,
})

const momentDocuments = import.meta.glob<string>(
  '/src/content/moments/**/index.md',
  { eager: true, import: 'default', query: '?raw' },
)

type MomentEntry = CollectionEntry<'moments'>

type RemoteImage = {
  height: number
  src: string
  width: number
}

const momentMediaMetadata = momentMediaMetadataJson as Record<
  string,
  Omit<RemoteImage, 'src'>
>

export type ResolvedMomentMedia =
  | {
      alt: string
      src: RemoteImage
      type: 'image'
    }
  | {
      alt: string
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
  publishedAt: Date
  publishedLabel: string
  provenanceUrl?: string
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

export function getMomentDateKey(date: Date) {
  const parts = getDateParts(date)
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
    .sort((first, second) => (
      first.dateKey.localeCompare(second.dateKey)
      || first.publishedAt.valueOf() - second.publishedAt.valueOf()
    ))
}

function resolveMoment(entry: MomentEntry): Moment {
  const source = parseMomentDocument(getMomentDocument(entry), { id: entry.id })
  const publishedDateKey = getMomentDateKey(source.publishedAt)
  const dateKey = source.occurredOn ?? publishedDateKey
  const moment: Moment = {
    dateKey,
    id: entry.id,
    media: source.media.map(item => resolveMedia(entry, item)),
    publishedAt: source.publishedAt,
    publishedLabel: formatPublishedLabel(source.publishedAt, dateKey),
    text: source.text,
  }

  if (source.provenance) {
    moment.provenanceUrl = source.provenance.url
  }

  if (source.location) {
    const locationId = source.location as LocationId
    const location = getLocation(locations, locationId)
    moment.locationId = locationId
    moment.location = { id: locationId, ...location }
  }

  return moment
}

function formatPublishedLabel(publishedAt: Date, dateKey: string) {
  const time = timeFormatter.format(publishedAt)

  if (getMomentDateKey(publishedAt) === dateKey) {
    return time
  }

  const parts = getDateParts(publishedAt)
  return `发布于 ${parts.year}/${Number(parts.month)}/${Number(parts.day)} ${time}`
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

  if (media.type === 'image') {
    return {
      alt: media.alt,
      src: resolveRemoteImage(key),
      type: 'image',
    }
  }

  const posterKey = media.poster
    ? `moments/${directory}/${media.poster}`
    : undefined

  return {
    alt: media.alt,
    src: getAssetUrl(key),
    type: 'video',
    ...(posterKey ? { poster: resolveRemoteImage(posterKey) } : {}),
  }
}

function resolveRemoteImage(key: string): RemoteImage {
  const metadata = momentMediaMetadata[key]

  if (!metadata) {
    throw new Error(`Missing Moment image metadata: ${key}`)
  }

  return {
    ...metadata,
    src: getAssetUrl(key),
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

function getDateParts(date: Date) {
  return Object.fromEntries(
    dateFormatter
      .formatToParts(date)
      .map(part => [part.type, part.value]),
  ) as Record<'day' | 'month' | 'year', string>
}
