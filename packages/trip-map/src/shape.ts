import type { MapLocation } from './locations.ts'
import { type Messages, zhCN } from './messages.ts'
import { getRouteLine, simplifyLine } from './player/route.ts'
import type {
  MapStats,
  PlaceMarker,
  TripData,
  TripDataStop,
  TripDataWaypoint,
  TripImage,
  TripImageInput,
  TripSummary,
  TripWeather,
} from './player/types.ts'
import {
  getDayOffset,
  getDayPlaces,
  type Trip,
  type TripMoment,
  type TripStop,
  type TripWaypoint,
} from './trips.ts'
import { describeWeather, type DayWeather } from './weather.ts'

// Turns a built trip into what the player plays (`TripData`) and what the overview
// draws (`TripSummary`). The site adds what only it knows: each stop's photos, words
// and links, the cover photos, the music and the cached weather.

/** What a site says about a stop beyond its places: its photos, words and where it lives. */
export type StopDetails = {
  /** The URL hash that opens the trip on this card, like `01-02-ferry-sunset`. */
  anchor: string
  /** Where the Moment itself can be read. */
  href: string
  images: TripImageInput[]
  text: string
  /** `HH:mm`, when the Moment has a time. */
  time?: string | undefined
  /** An embeddable video player's URL, played on the card instead of photos. */
  video?: string | undefined
}

export type ShapeTripOptions<T extends TripMoment> = {
  /** Photos for the intro and summary cards, best first. */
  covers: TripImageInput[]
  describeStop: (stop: TripStop<T>) => StopDetails
  /** For the weather labels; the Chinese defaults otherwise. */
  messages?: Messages
  /** The background music's URL. */
  music?: string | undefined
  /** A day's cached weather at a place (see `getWeatherKey`), when the site has it. */
  weather?: (dateKey: string, place: MapLocation) => DayWeather | undefined
}

export function shapeTripData<T extends TripMoment>(
  trip: Trip<T>,
  options: ShapeTripOptions<T>,
): TripData {
  const messages = options.messages ?? zhCN
  const stops = trip.stops.map((stop): TripDataStop => {
    const { images, time, video, ...details } = options.describeStop(stop)

    return {
      ...details,
      images: images.map(toTripImage),
      dateKey: stop.dateKey,
      day: getDayOffset(trip.start, stop.dateKey) + 1,
      places: stop.locations.map(toTripPlace),
      ...(time === undefined ? {} : { time }),
      ...(video === undefined ? {} : { video }),
      waypoints: stop.waypoints.map((waypoint) => toTripWaypoint(trip.start, waypoint)),
    }
  })

  return {
    covers: options.covers.map(toTripImage),
    dayCount: trip.dayCount,
    drivingHours: trip.drivingHours,
    drivingKm: trip.drivingKm,
    end: trip.end,
    id: trip.id,
    legs: trip.legs.map((leg) => ({ line: leg.route?.coordinates ?? null, mode: leg.by })),
    music: options.music,
    photoCount: stops.reduce((count, stop) => count + stop.images.length, 0),
    placeCount: trip.placeCount,
    start: trip.start,
    stops,
    title: trip.title,
    trailingWaypoints: trip.trailingWaypoints.map((waypoint) =>
      toTripWaypoint(trip.start, waypoint),
    ),
    weather: getTripWeather(trip, options.weather, messages),
  }
}

// The overview map draws every trip's whole route at once, so it gets a thinned copy:
// straying a couple hundred metres from the road only shows when zoomed in close.
const OVERVIEW_TOLERANCE = 0.002

/** What the overview needs of every trip: its route to draw and click, and its name. */
export function getTripSummary(trip: Trip<TripMoment>): TripSummary {
  const points = trip.path.map((location) => ({
    coordinates: [location.longitude, location.latitude] as const,
  }))
  const legs = trip.legs.map((leg) => leg.route?.coordinates ?? null)
  // Consecutive drives make one stretch; each ferry or flight is a stretch of its own,
  // drawn dashed so it doesn't read as a road.
  const segments: { line: [number, number][]; straight: boolean }[] = []
  // A ferry or flight taken both ways is drawn once: two dashed lines on top of each
  // other drift out of step and read as a solid one.
  const straightLegs = new Set<string>()
  // Set after a leg is left out, so the drive after it starts a stretch of its own.
  let gap = false

  for (const [index, leg] of trip.legs.entries()) {
    const straight = leg.by !== 'car'
    const line = getRouteLine(points, legs, index, index + 2)
    const last = segments.at(-1)

    if (straight) {
      const ends = [leg.from.id, leg.to.id].sort().join(' ')

      if (straightLegs.has(ends)) {
        gap = true
        continue
      }

      straightLegs.add(ends)
    }

    if (last && !last.straight && !straight && !gap) {
      last.line.push(...line.slice(1))
    } else {
      segments.push({ line, straight })
    }

    gap = false
  }

  if (segments.length === 0 && points.length > 0) {
    segments.push({ line: getRouteLine(points, legs, 0, points.length), straight: false })
  }

  return {
    end: trip.end,
    id: trip.id,
    segments: segments.map(({ line, straight }) => ({
      line: simplifyLine(line, OVERVIEW_TOLERANCE).map(
        ([longitude, latitude]) => [round(longitude), round(latitude)] as [number, number],
      ),
      straight,
    })),
    start: trip.start,
    title: trip.title,
  }
}

function round(degrees: number) {
  return Math.round(degrees * 1e4) / 1e4
}

/** Each trip day's weather, by day number; days without cached weather are left out. */
function getTripWeather(
  trip: Trip<TripMoment>,
  lookup: ShapeTripOptions<TripMoment>['weather'],
  messages: Messages,
) {
  const days: TripWeather = {}

  for (const [dateKey, place] of lookup ? getDayPlaces(trip) : []) {
    const day = lookup!(dateKey, place)
    const weather = day && describeWeather(day, messages)

    if (weather) {
      days[getDayOffset(trip.start, dateKey) + 1] = weather
    }
  }

  return days
}

/** Fills in what a site left out of an image: one source for every width, itself as its thumbnail. */
export function toTripImage(image: TripImageInput): TripImage {
  return {
    alt: image.alt ?? '',
    height: image.height,
    src: image.src,
    srcset: image.srcset ?? `${image.src} ${image.width}w`,
    thumbnail: image.thumbnail ?? image.src,
    ...(image.video ? { video: image.video } : {}),
    width: image.width,
  }
}

/** A place and its posts, as `groupMomentsByLocation` groups them. */
export type PlaceGroup = Readonly<{
  id: string
  firstYear: number
  location: MapLocation
  moments: readonly Readonly<{ dateKey: string }>[]
}>

/**
 * The places for the map page, from `groupMomentsByLocation`'s groups: each with its
 * posts counted and coloured by the year it was last visited (see `createYearPalette`).
 */
export function getPlaceMarkers(
  groups: readonly PlaceGroup[],
  yearColor: (year: number) => string,
): PlaceMarker[] {
  return groups.map((group) => ({
    color: yearColor(group.firstYear),
    count: group.moments.length,
    id: group.id,
    firstYear: group.firstYear,
    latitude: group.location.latitude,
    longitude: group.location.longitude,
    name: group.location.name,
    // Newest first, for narrowing the map to a month.
    months: [...new Set(group.moments.map((moment) => moment.dateKey.slice(0, 7)))].sort(
      (first, second) => second.localeCompare(first),
    ),
    // Newest first, for the drawer's line of totals.
    years: [...new Set(group.moments.map((moment) => getYear(moment.dateKey)))].sort(
      (first, second) => second - first,
    ),
  }))
}

/** The totals a screenshot of the map puts in its header. */
export function getMapStats(
  groups: readonly PlaceGroup[],
  trips: readonly Readonly<{ drivingKm: number }>[],
): MapStats {
  const moments = new Set(groups.flatMap((group) => group.moments))
  const years = [...moments].map((moment) => getYear(moment.dateKey))

  return {
    drivingKm: trips.reduce((total, trip) => total + trip.drivingKm, 0),
    firstYear: years.length > 0 ? Math.min(...years) : 0,
    lastYear: years.length > 0 ? Math.max(...years) : 0,
    moments: moments.size,
    places: groups.length,
    trips: trips.length,
  }
}

function getYear(dateKey: string) {
  return Number(dateKey.slice(0, 4))
}

function toTripPlace(location: MapLocation) {
  return { latitude: location.latitude, longitude: location.longitude, name: location.name }
}

function toTripWaypoint(start: string, waypoint: TripWaypoint): TripDataWaypoint {
  return {
    ...toTripPlace(waypoint.location),
    dateKey: waypoint.dateKey,
    day: getDayOffset(start, waypoint.dateKey) + 1,
    time: waypoint.time,
  }
}
