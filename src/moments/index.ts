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
import { getAssetUrl } from '@/asset-url'
import { locations, type LocationId } from '@/data/locations'
import type { MomentMedia, PublishedImage, PublishedVideo } from '@/data/spec'
import { trips } from '@/data/trips'
import tripRoutes from '@/data/trip-routes.json'

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

/** One static path per trip, for the /trip/<id>/ page and the files beside it. */
export async function getTripPaths() {
  return getTrips(await getMoments()).map((trip) => ({ params: { id: trip.id }, props: { trip } }))
}

export type TripPathProps = Awaited<ReturnType<typeof getTripPaths>>[number]['props']

/** A trip with its Moments and route, as `getTrips` builds it. */
export type MomentTrip = ReturnType<typeof getTrips>[number]

export function getTrips(moments: Moment[]) {
  return trips
    .map((trip) =>
      buildTrip(trip, moments, {
        resolveLocation: (id) => ({ id, ...getLocation(locations, id as LocationId) }),
        // JSON coordinates are inferred as number[][]; the routes script writes pairs.
        routes: tripRoutes as unknown as Record<string, TripRoute>,
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

export function getMonthPath(monthKey: string) {
  return `/calendar/${monthKey.replace('-', '/')}/`
}

/** The years with Moments, newest first: one timeline page each. */
export function getTimelineYears(moments: Moment[]) {
  return [...new Set(moments.map((moment) => moment.dateKey.slice(0, 4)))].sort().reverse()
}

export function getTimelinePath(year: string) {
  return `/timeline/${year}/`
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
        ...(poster ? { poster: resolveRemoteImage(poster) } : {}),
      }
    }
  }
}

/** An image's sources for `srcset`: its variants, and the original when it isn't oversized. */
function resolveRemoteImage(image: PublishedImage): RemoteImage {
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
