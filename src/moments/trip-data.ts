import { SITE_TITLE } from '@/consts'
import { tripVideoMusic } from '@/data/music'
import tripWeather from '@/data/trip-weather.json'
import { getMomentPath, type MomentTrip } from '@/moments'
import { takeBilibiliVideo } from '@/moments/bilibili'
import { getTripCovers, getTripImages } from '@/moments/trip-images'
import {
  describeWeather,
  getDayOffset,
  getDayPlaces,
  getRouteLine,
  getWeatherKey,
  simplifyLine,
  type DayWeather,
  type TripData,
  type TripSummary,
  type TripWaypoint,
  type TripWeather,
} from '@hyoban/trip-map'

// What the trip player needs for one trip. The map page inlines it for the trip it
// opens on and fetches /trip/<id>/data.json for the others.

export function getTripData(trip: MomentTrip): TripData {
  return {
    dateRange: `${formatShortDate(trip.start)} – ${formatShortDate(trip.end)}`,
    dayCount: trip.dayCount,
    drivingKm: trip.drivingKm,
    drivingHours: trip.drivingHours,
    id: trip.id,
    music: getTripMusic(trip),
    pageTitle: getTripTitle(trip.title),
    photoCount: trip.stops.reduce((count, stop) => count + stop.media.length, 0),
    placeCount: trip.placeCount,
    covers: getTripCovers(trip),
    stops: trip.stops.map((stop) => ({
      ...getStopWords(stop.text),
      anchor: getStopAnchor(stop.id),
      date: formatShortDate(stop.dateKey, true),
      day: getDayOffset(trip.start, stop.dateKey) + 1,
      href: getMomentPath(stop.dateKey),
      images: getTripImages(stop.media),
      name: stop.locations.map((location) => location.name).join('、'),
      places: stop.locations.map(toTripPlace),
      time: stop.occurredLabel,
      waypoints: stop.waypoints.map((waypoint) => toTripWaypoint(trip.start, waypoint)),
    })),
    title: trip.title,
    trailingWaypoints: trip.trailingWaypoints.map((waypoint) =>
      toTripWaypoint(trip.start, waypoint),
    ),
    // Road or rail geometry from one place to the next; null means draw it straight.
    legs: trip.legs.map((leg) => leg.route?.coordinates ?? null),
    legModes: trip.legs.map((leg) => leg.by),
    weather: getTripWeather(trip),
  }
}

// The overview map draws every trip's whole route at once, so it gets a thinned copy:
// straying a couple hundred metres from the road only shows when zoomed in close.
const OVERVIEW_TOLERANCE = 0.002

/** What the map overview needs for every trip: its route to draw and click, and its name. */
export function getTripSummary(trip: MomentTrip): TripSummary {
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
    dateRange: `${formatShortDate(trip.start)} – ${formatShortDate(trip.end)}`,
    id: trip.id,
    pageTitle: getTripTitle(trip.title),
    segments: segments.map(({ line, straight }) => ({
      line: simplifyLine(line, OVERVIEW_TOLERANCE).map(
        ([longitude, latitude]) => [round(longitude), round(latitude)] as [number, number],
      ),
      straight,
    })),
    title: trip.title,
  }
}

function round(degrees: number) {
  return Math.round(degrees * 1e4) / 1e4
}

/** Each trip day's weather, by day number; days `pnpm trips:weather` hasn't fetched are left out. */
function getTripWeather(trip: MomentTrip) {
  const days: TripWeather = {}

  for (const [dateKey, place] of getDayPlaces(trip)) {
    const day = (tripWeather as Record<string, DayWeather>)[getWeatherKey(dateKey, place)]
    const weather = day && describeWeather(day)

    if (weather) {
      days[getDayOffset(trip.start, dateKey) + 1] = weather
    }
  }

  return days
}

/** The URL hash that opens a trip on this Moment's card, like `01-02-ferry-sunset`. */
export function getStopAnchor(momentId: string) {
  return momentId.slice(momentId.lastIndexOf('/') + 1)
}

export function getTripTitle(tripTitle: string) {
  return `${tripTitle} · 旅程 | ${SITE_TITLE}`
}

function toTripPlace(location: { latitude: number; longitude: number; name: string }) {
  return { latitude: location.latitude, longitude: location.longitude, name: location.name }
}

function toTripWaypoint(start: string, waypoint: TripWaypoint) {
  return {
    ...toTripPlace(waypoint.location),
    date: formatShortDate(waypoint.dateKey, true),
    day: getDayOffset(start, waypoint.dateKey) + 1,
    time: waypoint.time,
  }
}

function getTripMusic(trip: { id: string; music?: string }) {
  if (!trip.music) {
    return undefined
  }

  const track = tripVideoMusic.find((item) => item.id === trip.music)

  if (!track) {
    throw new Error(`Unknown music "${trip.music}" in trip "${trip.id}".`)
  }

  return track.src
}

function formatShortDate(dateKey: string, weekday = false) {
  return new Intl.DateTimeFormat('zh-CN', {
    day: 'numeric',
    month: 'long',
    timeZone: 'UTC',
    ...(weekday ? { weekday: 'short' as const } : {}),
  }).format(new Date(`${dateKey}T00:00:00Z`))
}

/** A stop's words, with a Bilibili video it links to taken out to play on its card. */
function getStopWords(text: string) {
  const words = takeBilibiliVideo(text, { autoplay: true })
  return { text: getPlainText(words.text), ...(words.video ? { video: words.video } : {}) }
}

function getPlainText(text: string) {
  return text
    .split('\n')
    .map((line) =>
      line
        .replaceAll(/\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replaceAll(/^[#>\s-]+|[*_`]/g, '')
        .trim(),
    )
    .filter(Boolean)
    .join('\n')
}
