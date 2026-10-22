import {
  getTripSummary,
  getWeatherKey,
  shapeTripData,
  type DayWeather,
  type TripData,
} from '@hyoban/trip-map'
import { tripVideoMusic } from '@/data/music'
import tripWeather from '@/data/trip-weather.json'
import { getMomentPath, getMoments, getTrips, type MomentTrip } from '@/moments'
import { takeBilibiliVideo } from '@/moments/bilibili'
import { getTripCovers, getTripImages } from '@/moments/trip-images'
import { tripMapMessages } from '@/trip-map'

// What the trip player needs for one trip. The map page inlines it for the trip it
// opens on and fetches /trip/<id>/data.json for the others.

export { getTripSummary }

export function getTripData(trip: MomentTrip): TripData {
  return shapeTripData(trip, {
    covers: getTripCovers(trip),
    describeStop: (stop) => ({
      ...getStopWords(stop.text),
      anchor: getStopAnchor(stop.id),
      href: getMomentPath(stop.dateKey),
      images: getTripImages(stop.media),
      time: stop.occurredLabel,
    }),
    messages: tripMapMessages,
    music: getTripMusic(trip),
    weather: (dateKey, place) =>
      (tripWeather as Record<string, DayWeather>)[getWeatherKey(dateKey, place)],
  })
}

type MomentTripLink = Readonly<{ id: string; title: string }>

let momentTrips: Promise<Map<string, MomentTripLink>> | undefined

/** Each Moment that's a stop of a trip, by its id: the trip's id and title. */
export function getMomentTrips() {
  momentTrips ??= getMoments().then(
    (moments) =>
      new Map(
        getTrips(moments).flatMap((trip) =>
          trip.stops.map((stop) => [stop.id, { id: trip.id, title: trip.title }]),
        ),
      ),
  )
  return momentTrips
}

/** The URL hash that opens a trip on this Moment's card, like `01-02-ferry-sunset`. */
export function getStopAnchor(momentId: string) {
  return momentId.slice(momentId.lastIndexOf('/') + 1)
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
