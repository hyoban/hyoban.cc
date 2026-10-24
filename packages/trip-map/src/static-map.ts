// The map framed on every place as the page first opens it, worked out without the
// map, and a picture of the map from the Mapbox Static Images API that a page can show
// while the map loads.

type Place = Readonly<{ latitude: number; longitude: number }>

export type FramingInset = Readonly<{ bottom?: number; legend?: number; right?: number }>

// Places are gathered into clusters up to this zoom (see place-markers.ts).
export const CLUSTER_MAX_ZOOM = 9
// Framing every place, or a period's, the map comes as close as their spread allows, but
// no closer than one city's places standing apart, out of their clusters.
export const ALL_PLACES_MAX_ZOOM = CLUSTER_MAX_ZOOM + 1
// A trip's route framed beside the trip list or its sidebar: no closer than a city's streets.
export const TRIP_FRAME_MAX_ZOOM = 9
// Between the legend and the lowest place framed: a cluster's radius and some.
const LEGEND_GAP = 28
// Mapbox's tiles are this many pixels wide at zoom 0.
const TILE_SIZE = 512

/** The room kept around every place framed, clear of the toolbar, the legend and a panel. */
export function getAllPlacesPadding(narrow: boolean, inset: FramingInset = {}) {
  // A phone held upright is short of width, so the sides get only a little room.
  const side = narrow ? 20 : 56

  return {
    // A panel along the foot is clear of the legend; otherwise a dot keeps a little
    // way above it.
    bottom:
      inset.bottom === undefined
        ? Math.max(80, (inset.legend ?? 0) + LEGEND_GAP)
        : 80 + inset.bottom,
    left: side,
    right: side + (inset.right ?? 0),
    top: 88,
  }
}

const toX = (longitude: number) => (longitude + 180) / 360
const toY = (latitude: number) => {
  const sin = Math.sin((latitude * Math.PI) / 180)
  return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)
}
const toLongitude = (x: number) => x * 360 - 180
const toLatitude = (y: number) => (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI

/**
 * Where `places` spread on a flat map, from 0 to 1 across the world: west to east, and
 * north to south.
 */
export function getPlacesExtent(places: readonly Place[]) {
  const xs = places.map((place) => toX(place.longitude))
  const ys = places.map((place) => toY(place.latitude))

  return {
    maxX: Math.max(...xs),
    maxY: Math.max(...ys),
    minX: Math.min(...xs),
    minY: Math.min(...ys),
  }
}

/**
 * The centre and zoom that frame `places` in a view `width` by `height` pixels, as the
 * map's own `fitBounds` does on a flat map.
 */
export function getAllPlacesCamera(
  places: readonly Place[],
  size: Readonly<{
    height: number
    inset?: FramingInset
    /** No closer than this; the map's own limit by default. */
    maxZoom?: number
    narrow: boolean
    width: number
  }>,
) {
  const { maxX, maxY, minX, minY } = getPlacesExtent(places)
  const padding = getAllPlacesPadding(size.narrow, size.inset)
  const roomX = size.width - padding.left - padding.right
  const roomY = size.height - padding.top - padding.bottom
  const zoom = Math.min(
    size.maxZoom ?? ALL_PLACES_MAX_ZOOM,
    Math.log2(roomX / (TILE_SIZE * Math.max(maxX - minX, 1e-9))),
    Math.log2(roomY / (TILE_SIZE * Math.max(maxY - minY, 1e-9))),
  )
  const world = TILE_SIZE * 2 ** zoom
  // The middle of the room left by the padding sits on the places' middle.
  const x = (minX + maxX) / 2 + (padding.right - padding.left) / 2 / world
  const y = (minY + maxY) / 2 + (padding.bottom - padding.top) / 2 / world

  return { latitude: toLatitude(y), longitude: toLongitude(x), x, y, zoom }
}

/**
 * Where a place falls in a view `width` by `height` pixels on `camera`, as framed by
 * getAllPlacesCamera: in pixels from the view's top left.
 */
export function projectOnCamera(
  camera: Readonly<{ x: number; y: number; zoom: number }>,
  size: Readonly<{ height: number; width: number }>,
  place: Place,
) {
  const world = TILE_SIZE * 2 ** camera.zoom

  return {
    x: size.width / 2 + (toX(place.longitude) - camera.x) * world,
    y: size.height / 2 + (toY(place.latitude) - camera.y) * world,
  }
}

export type StaticMapCamera = Readonly<{ latitude: number; longitude: number; zoom: number }>

export type StaticMapOptions = Readonly<{
  height: number
  /** Drawn at twice the size, `width` by `height` at the screen's pixels. */
  retina?: boolean | undefined
  /** A Mapbox style, `mapbox/light-v11` or `mapbox/dark-v11` as the map uses. */
  style: string
  token: string
  width: number
}>

/**
 * A picture of the map at `camera`, `width` by `height` pixels, from the Mapbox Static
 * Images API, for a page to show before the map has loaded. Its labels are in the
 * style's own language, which the API can't change.
 */
export function getStaticMapUrl(camera: StaticMapCamera, options: StaticMapOptions) {
  const view = [camera.longitude.toFixed(5), camera.latitude.toFixed(5), camera.zoom.toFixed(2)]
  const url = new URL(
    `https://api.mapbox.com/styles/v1/${options.style}/static/${view.join(',')}/${options.width}x${options.height}${options.retina ? '@2x' : ''}`,
  )

  // The map that replaces it carries the attribution.
  url.searchParams.set('attribution', 'false')
  url.searchParams.set('logo', 'false')
  url.searchParams.set('access_token', options.token)

  return url.href
}
