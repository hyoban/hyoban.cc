import { ALL_PLACES_MAX_ZOOM, getAllPlacesCamera, getPlacesExtent } from '@hyoban/trip-map'
import { getMapMonthKeys, getMapYears, getMappedLocationGroups, getMoments } from '@/moments'

// The pictures the map pages show while their map loads, in each theme: the map around
// the places it opens framed on, from the Mapbox Static Images API, drawn at build time
// a little further out than any window frames them, so the page can scale one to the
// view the map opens on. One for every place, `all`, and one for a period the map can
// open narrowed to, as `2025` or `2025-06`, unless one already made covers it at nearly
// the zoom it needs.

export const MAP_PLACEHOLDER_SIZE = 1280
// How much closer or further out than a square window of the picture's size frames the
// places a phone held upright and a wide screen do, give or take, and how much of each
// the picture must cover from there.
const PHONE = { cover: 1000, zoom: -1.8 }
const WIDE = { cover: 2600, zoom: 1.1 }
// How much closer a period may be than a wider picture it shares, scaled up the more.
const MAX_SHARED_ZOOM_IN = 1

export const mapPlaceholderThemes = [
  { name: 'light', style: 'mapbox/light-v11' },
  { name: 'dark', style: 'mapbox/dark-v11' },
] as const

export const getMapPlaceholderPath = (
  key: string,
  theme: (typeof mapPlaceholderThemes)[number]['name'],
) => `/map/placeholder/${key}-${theme}.webp`

/** The key of a period's picture: `all` without one. */
export function getMapPlaceholderKey(period?: { month?: number | undefined; year: number }) {
  if (!period) return 'all'
  return period.month === undefined
    ? String(period.year)
    : `${period.year}-${String(period.month).padStart(2, '0')}`
}

/** Every period the map can open on, every place first, then each year, then each month. */
async function getPeriodKeys() {
  const moments = await getMoments()
  return ['all', ...getMapYears(moments).map(String), ...getMapMonthKeys(moments).toSorted()]
}

/** The periods with pictures of their own: the others share one. */
export async function getMapPlaceholderKeys() {
  return [...new Set((await getPictures()).values())].map((picture) => picture.key)
}

/** The places a period leaves on the map, as the map frames them: every one if none. */
async function getFramedPlaces(key: string) {
  const groups = getMappedLocationGroups(await getMoments())
  const shown =
    key === 'all'
      ? []
      : groups.filter((group) => group.moments.some((moment) => moment.dateKey.startsWith(key)))

  return (shown.length > 0 ? shown : groups).map((group) => group.location)
}

type Picture = {
  key: string
  latitude: number
  longitude: number
  x: number
  y: number
  zoom: number
}

/** Where a period's own picture would be centred, and at what zoom. */
async function getOwnPicture(key: string): Promise<Picture> {
  const places = await getFramedPlaces(key)
  const size = { height: MAP_PLACEHOLDER_SIZE, narrow: false, width: MAP_PLACEHOLDER_SIZE }
  const framed = getAllPlacesCamera(places, size)
  // As close as the picture can be and still cover both: no further out than it must be,
  // as it's scaled up from there.
  const closest = getAllPlacesCamera(places, { ...size, maxZoom: Infinity }).zoom
  const zoom = Math.min(
    ...[PHONE, WIDE].map(
      (view) =>
        Math.min(ALL_PLACES_MAX_ZOOM, closest + view.zoom) -
        Math.log2(view.cover / MAP_PLACEHOLDER_SIZE),
    ),
  )

  return {
    key,
    latitude: framed.latitude,
    longitude: framed.longitude,
    x: framed.x,
    y: framed.y,
    zoom,
  }
}

/** Whether `wider` holds all of `own` and is no more than a little further out. */
function covers(wider: Picture, own: Picture) {
  const zoomIn = own.zoom - wider.zoom
  // Half a picture's width, from 0 to 1 across the world, at a zoom.
  const half = (zoom: number) => MAP_PLACEHOLDER_SIZE / 2 / (512 * 2 ** zoom)

  return (
    zoomIn >= 0 &&
    zoomIn <= MAX_SHARED_ZOOM_IN &&
    Math.abs(own.x - wider.x) + half(own.zoom) <= half(wider.zoom) &&
    Math.abs(own.y - wider.y) + half(own.zoom) <= half(wider.zoom)
  )
}

let pictures: Promise<Map<string, Picture>> | undefined

/**
 * The picture each period shows: the widest one made before it that covers it (a city's
 * months share one), or its own.
 */
function getPictures() {
  pictures ??= (async () => {
    const byKey = new Map<string, Picture>()
    const own: Picture[] = []

    for (const key of await getPeriodKeys()) {
      const picture = await getOwnPicture(key)
      const shared = own.find((wider) => covers(wider, picture))

      if (!shared) own.push(picture)
      byKey.set(key, shared ?? picture)
    }

    return byKey
  })()

  return pictures
}

/**
 * The picture a period shows and where it's centred and at what zoom, and where the
 * period's places spread on it.
 */
export async function getMapPlaceholderView(key: string) {
  const picture = (await getPictures()).get(key) ?? (await getOwnPicture(key))

  return {
    camera: { latitude: picture.latitude, longitude: picture.longitude, zoom: picture.zoom },
    center: { x: picture.x, y: picture.y },
    extent: getPlacesExtent(await getFramedPlaces(key)),
    picture: picture.key,
  }
}
