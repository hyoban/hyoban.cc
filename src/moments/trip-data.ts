import { SITE_TITLE } from '@/consts'
import { tripVideoMusic } from '@/data/music'
import tripWeather from '@/data/trip-weather.json'
import { getMomentPath, type MomentTrip } from '@/moments'
import { takeBilibiliVideo } from '@/moments/bilibili'
import { getTripCovers, getTripImages } from '@/moments/trip-images'
import { getDayOffset, getDayPlaces, getWeatherKey, type TripWaypoint } from '@/moments/trips'

// What the trip player needs for one trip. The map page inlines it for the trip it
// opens on and fetches /trip/<id>/data.json for the others.

export function getTripData(trip: MomentTrip) {
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
    // Road geometry from one place to the next; null means draw it straight.
    legs: trip.legs.map((leg) => leg.route?.coordinates ?? null),
    legModes: trip.legs.map((leg) => leg.by),
    weather: getTripWeather(trip),
  }
}

type DayWeather = { code: number; max: number; min: number }

// WMO weather codes, as Open-Meteo reports them, grouped coarsely.
const WEATHER_KINDS: [maxCode: number, label: string, icon: string][] = [
  [0, '晴', 'i-lucide-sun'],
  [2, '多云', 'i-lucide-cloud-sun'],
  [3, '阴', 'i-lucide-cloud'],
  [48, '雾', 'i-lucide-cloud-fog'],
  [57, '毛毛雨', 'i-lucide-cloud-drizzle'],
  [67, '雨', 'i-lucide-cloud-rain'],
  [77, '雪', 'i-lucide-snowflake'],
  [82, '阵雨', 'i-lucide-cloud-rain'],
  [86, '阵雪', 'i-lucide-snowflake'],
  [99, '雷雨', 'i-lucide-cloud-lightning'],
]

/** Each trip day's weather, by day number; days `pnpm trips:weather` hasn't fetched are left out. */
function getTripWeather(trip: MomentTrip) {
  const days: Record<number, { icon: string; label: string }> = {}

  for (const [dateKey, place] of getDayPlaces(trip)) {
    const day = (tripWeather as Record<string, DayWeather>)[getWeatherKey(dateKey, place)]
    const kind = day && WEATHER_KINDS.find(([maxCode]) => day.code <= maxCode)

    if (day && kind) {
      days[getDayOffset(trip.start, dateKey) + 1] = {
        icon: kind[2],
        label: `${kind[1]} · ${day.min}–${day.max}°C`,
      }
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
