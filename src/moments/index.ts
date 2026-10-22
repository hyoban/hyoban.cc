import {
  buildTrip,
  compareMoments,
  getLocation,
  groupMomentsByLocation,
  type MapLocation,
  type TripRoute,
} from '@hyoban/trip-map'
import type { CollectionEntry } from 'astro:content'
import { getCollection } from 'astro:content'
import { getAssetUrl, getFrameSources, getResizedSources } from '@/asset-url'
import { locations, type LocationId } from '@/data/locations'
import type { MomentMedia, PublishedImage, PublishedVideo } from '@hyoban/data-spec'
import { trips } from '@/data/trips'
import { readRoutes } from '@/data/store'

const calendarDateFormatter = new Intl.DateTimeFormat('en-CA', {
  day: '2-digit',
  month: '2-digit',
  timeZone: 'Asia/Singapore',
  year: 'numeric',
})

type MomentEntry = CollectionEntry<'moments'>

type RemoteImage = {
  height: number
  sources: {
    src: string
    width: number
  }[]
  src: string
  width: number
}

export type ResolvedMomentMedia =
  | {
      alt: string
      src: RemoteImage
      type: 'live-photo'
      uid: string
      video: { src: string; contentType: string }
    }
  | {
      alt: string
      src: RemoteImage
      type: 'image'
      uid: string
    }
  | {
      alt: string
      contentType: string
      poster?: RemoteImage
      src: string
      type: 'video'
      uid: string
    }

export type Moment = {
  dateKey: string
  /** The published id, `YYYY/MM/DD-NN-slug`: the R2 directory and the page anchor. */
  id: string
  /** Places in visiting order; empty when the Moment has no location. */
  locations: ResolvedMomentLocation[]
  media: ResolvedMomentMedia[]
  occurredAt: string
  occurredLabel?: string
  order: number
  text: string
  /** The record's ULID, as trips' covers name it. */
  uid: string
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
  return [...new Set(moments.map((moment) => moment.dateKey))]
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

/** The years the map can be narrowed to: those with Moments at a place on it, oldest first. */
export function getMapYears(moments: Moment[]) {
  const years = getMappedLocationGroups(moments).flatMap((group) =>
    group.moments.map((moment) => Number.parseInt(moment.dateKey.slice(0, 4), 10)),
  )

  return [...new Set(years)].sort((first, second) => first - second)
}

/** One static path per trip, for the /trip/<id>/ page and the files beside it. */
export async function getTripPaths() {
  return getTrips(await getMoments()).map((trip) => ({ params: { id: trip.id }, props: { trip } }))
}

export type TripPathProps = Awaited<ReturnType<typeof getTripPaths>>[number]['props']

/** The same pages by a trip's year and the rest of its id: /trip/2026/yunnan/. */
export async function getNestedTripPaths() {
  return (await getTripPaths()).flatMap(({ props }) => {
    const [, year, slug] = props.trip.id.match(/^(\d{4})-(.+)$/) ?? []
    return year && slug ? [{ params: { slug, year }, props }] : []
  })
}

/** A trip with its Moments and route, as `getTrips` builds it. */
export type MomentTrip = ReturnType<typeof getTrips>[number]

/** The legs' routes the API fetched, read once; the spec's optional `duration` is only ever left out. */
const tripRoutes = readRoutes() as Readonly<Record<string, TripRoute>>

export function getTrips(moments: Moment[]) {
  return trips
    .map((trip) =>
      buildTrip(trip, moments, {
        resolveLocation: (id) => ({ id, ...getLocation(locations, id as LocationId) }),
        routes: tripRoutes,
      }),
    )
    .filter((trip) => trip.stops.length > 0)
}

export function getCalendarDateKey(date: Date) {
  const parts = Object.fromEntries(
    calendarDateFormatter.formatToParts(date).map((part) => [part.type, part.value]),
  ) as Record<'day' | 'month' | 'year', string>

  return `${parts.year}-${parts.month}-${parts.day}`
}

export function getMomentPath(dateKey: string) {
  return `/calendar/${dateKey.replaceAll('-', '/')}/`
}

/**
 * A Moment's own page, to share: its day in the calendar with the rest of its published
 * id, `YYYY/MM/DD-NN-slug`, which it keeps once it has one, below it.
 */
export function getMomentPagePath(moment: Pick<Moment, 'id'>) {
  return `/calendar/${moment.id.replace('-', '/')}/`
}

/**
 * Whether the build draws each Moment's share card. Fetching every photo and font subset
 * makes a build minutes longer, so only CI's builds (Cloudflare's and the checks') draw
 * them; elsewhere a Moment's page shares the site's card.
 */
export const MOMENT_OG_IMAGES = Boolean(process.env.CI)

/** One static path per Moment, for /calendar/<YYYY>/<MM>/<DD>/<NN-slug>/ and its share card. */
export async function getMomentPagePaths() {
  return (await getMoments()).map((moment) => {
    const [year, month, day, slug] = getMomentPagePath(moment).split('/').slice(2, 6) as [
      string,
      string,
      string,
      string,
    ]
    return { params: { day, month, moment: slug, year }, props: { moment } }
  })
}

export type MomentPagePathProps = Awaited<ReturnType<typeof getMomentPagePaths>>[number]['props']

/** The Moment's words on one line, cut to `length` characters, for titles and descriptions. */
export function getMomentExcerpt(moment: Moment, length: number) {
  const text = moment.text.replace(/\s+/g, ' ').trim()
  const characters = Array.from(
    new Intl.Segmenter('zh-CN').segment(text),
    (segment) => segment.segment,
  )
  return characters.length > length ? `${characters.slice(0, length - 1).join('')}…` : text
}

/** When and where, as a Moment's page and share card show it: `2023 年 12 月 13 日 08:45 · 上海`. */
export function getMomentDateLabel(moment: Moment) {
  const [year, month, day] = moment.dateKey.split('-').map(Number)
  const date = `${year} 年 ${month} 月 ${day} 日${moment.occurredLabel ? ` ${moment.occurredLabel}` : ''}`
  return [date, ...moment.locations.map((location) => location.name)].join(' · ')
}

export function getMonthPath(monthKey: string) {
  return `/calendar/${monthKey.replace('-', '/')}/`
}

async function loadMoments() {
  const entries = await getCollection('moments', ({ data }) => !data.hidden)

  return entries.flatMap((entry) => resolveMoment(entry) ?? []).sort(compareMoments)
}

/** A record as the pages use it; a draft that isn't published yet is left out with a warning. */
function resolveMoment(entry: MomentEntry): Moment | undefined {
  const record = entry.data

  if (!record.published) {
    console.warn(`Skipping unpublished Moment ${record.uid} (${record.occurredAt}).`)
    return undefined
  }

  const dateKey = record.occurredAt.slice(0, 10)
  const moment: Moment = {
    dateKey,
    id: record.published.id,
    locations: record.places.map((id) => {
      const locationId = id as LocationId
      return { id: locationId, ...getLocation(locations, locationId) }
    }),
    media: record.media.map((media) => resolveMedia(record.uid, media)),
    occurredAt: record.occurredAt,
    order: record.published.order,
    text: record.text,
    uid: record.uid,
  }

  if (record.occurredAt.length > 10) {
    moment.occurredLabel = record.occurredAt.slice(11, 16)
  }

  return moment
}

function resolveMedia(momentUid: string, media: MomentMedia): ResolvedMomentMedia {
  if (!media.published) {
    throw new Error(`Published Moment ${momentUid} has unpublished media ${media.uid}.`)
  }

  switch (media.type) {
    case 'image':
      return {
        alt: media.alt,
        src: resolveRemoteImage(media.published),
        type: 'image',
        uid: media.uid,
      }
    case 'live-photo': {
      const { video, ...still } = media.published

      return {
        alt: media.alt,
        src: resolveRemoteImage(still),
        type: 'live-photo',
        uid: media.uid,
        video: { src: getAssetUrl(video.key), contentType: video.contentType },
      }
    }
    case 'video': {
      const { poster, ...video } = media.published

      return {
        alt: media.alt,
        contentType: video.contentType,
        src: getAssetUrl(video.key),
        type: 'video',
        uid: media.uid,
        ...(poster
          ? { poster: resolveRemoteImage(poster) }
          : video.original && media.width && media.height
            ? { poster: resolveFrame(video.key, media.width, media.height) }
            : {}),
      }
    }
  }
}

/** A kept video's first frame as its poster, the largest size standing in for the whole. */
function resolveFrame(key: string, width: number, height: number): RemoteImage {
  const sources = getFrameSources(key, width)

  return { height, sources, src: sources.at(-1)!.src, width }
}

/**
 * An image's sources for `srcset`: its variants, and the original when it isn't
 * oversized; or, for an original the API kept, its sizes from Cloudflare Images, the
 * largest of them standing in for the original.
 */
function resolveRemoteImage(image: PublishedImage): RemoteImage {
  if (image.original) {
    const sources = getResizedSources(image)

    return { height: image.height, sources, src: sources.at(-1)!.src, width: image.width }
  }

  const sources = [
    ...image.variants.map((variant) => ({ src: getAssetUrl(variant.key), width: variant.width })),
    ...(image.width <= 1920 ? [{ src: getAssetUrl(image.key), width: image.width }] : []),
  ]
    .sort((first, second) => first.width - second.width)
    .filter((source, index, items) => index === 0 || source.width !== items[index - 1]!.width)

  return {
    height: image.height,
    sources,
    src: getAssetUrl(image.key),
    width: image.width,
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

export type { PublishedVideo }
