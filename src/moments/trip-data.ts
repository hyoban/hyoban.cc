import { SITE_TITLE } from '@/consts'
import { tripVideoMusic } from '@/data/music'
import { getMomentPath, type getTrips } from '@/moments'
import { getTripCovers, getTripImages } from '@/moments/trip-images'
import { getDayOffset, type TripWaypoint } from '@/moments/trips'

// What the trip player needs for one trip. The map page inlines it for the trip it
// opens on and fetches /trip/<id>/data.json for the others.

type BuiltTrip = ReturnType<typeof getTrips>[number]

export function getTripData(trip: BuiltTrip) {
  return {
    dateRange: `${formatShortDate(trip.start)} – ${formatShortDate(trip.end)}`,
    dayCount: trip.dayCount,
    distanceKm: trip.distanceKm,
    drivingHours: trip.drivingHours,
    id: trip.id,
    music: getTripMusic(trip),
    pageTitle: getTripTitle(trip.title),
    photoCount: trip.stops.reduce((count, stop) => count + stop.media.length, 0),
    placeCount: trip.placeCount,
    covers: getTripCovers(trip),
    stops: trip.stops.map((stop) => ({
      date: formatShortDate(stop.dateKey, true),
      day: getDayOffset(trip.start, stop.dateKey) + 1,
      href: getMomentPath(stop.dateKey),
      images: getTripImages(stop.media),
      name: stop.locations.map((location) => location.name).join('、'),
      places: stop.locations.map(toTripPlace),
      text: getPlainText(stop.text),
      time: stop.occurredLabel,
      waypoints: stop.waypoints.map((waypoint) => toTripWaypoint(trip.start, waypoint)),
    })),
    title: trip.title,
    trailingWaypoints: trip.trailingWaypoints.map((waypoint) =>
      toTripWaypoint(trip.start, waypoint),
    ),
    // Road geometry from one place to the next; null means draw it straight.
    legs: trip.legs.map((leg) => leg.route?.coordinates ?? null),
  }
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
