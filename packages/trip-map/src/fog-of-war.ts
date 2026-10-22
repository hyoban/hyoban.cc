import { toCoordinates, type LngLatLike } from './geo.ts'

// The fog of war over the map: every city under it but those a post was written in.
// The cities are regions of a GeoJSON collection the site gives, each with an `id`,
// and perhaps one `fogged`, the rest of the world, never lit; the map draws the fog as
// those regions not lit, so it needs no geometry worked out, only which ids are lit.
// A place on no city, on the coast the boundaries were simplified past, lights the
// nearest within reach. The Android app finds them the same way (apps/android,
// `ui/map/FogOfWar.kt`).

type Ring = readonly (readonly number[])[]

/** How far, in degrees, a place on no city may be from the nearest one's edge: about 10 km. */
const REACH = 0.1

/** A region to light: a city, with its `id`; ids sharing their first two digits share a province. */
export type FogRegion = Readonly<{
  geometry:
    | Readonly<{ coordinates: readonly Ring[]; type: 'Polygon' }>
    | Readonly<{ coordinates: readonly (readonly Ring[])[]; type: 'MultiPolygon' }>
  properties: Readonly<{ fogged?: boolean; id: string; name: string }>
}>

export type FogRegions = Readonly<{ features: readonly FogRegion[] }>

export type FogOfWar = Readonly<{
  /** How many cities are lit. */
  cities: number
  /** The ids of the regions lit. */
  lit: string[]
  /** How many provinces have a city lit. */
  provinces: number
}>

/** The regions holding any of `places`, and how many cities and provinces they make. */
export function buildFogOfWar(regions: FogRegions, places: Iterable<LngLatLike>): FogOfWar {
  const lit = new Set<string>()
  const indexed = regions.features
    .filter((region) => !region.properties.fogged)
    .map((region) => ({ bounds: getBounds(region), region }))

  for (const place of places) {
    const point = toCoordinates(place)
    const found =
      indexed.find(
        ({ bounds, region }) =>
          point[0] >= bounds[0] &&
          point[0] <= bounds[2] &&
          point[1] >= bounds[1] &&
          point[1] <= bounds[3] &&
          containsPoint(region, point),
      ) ?? findNearest(indexed, point)

    if (found) {
      lit.add(found.region.properties.id)
    }
  }

  const ids = [...lit].sort()

  return {
    cities: ids.length,
    lit: ids,
    provinces: new Set(ids.map((id) => id.slice(0, 2))).size,
  }
}

/** The region with an edge nearest `point`, within `REACH` of it. */
function findNearest<T extends { bounds: readonly number[]; region: FogRegion }>(
  indexed: readonly T[],
  [x, y]: readonly [number, number],
) {
  let nearest: T | undefined
  let best = REACH

  for (const entry of indexed) {
    const [west = 0, south = 0, east = 0, north = 0] = entry.bounds

    if (x < west - best || x > east + best || y < south - best || y > north + best) {
      continue
    }

    for (const ring of getPolygons(entry.region).flat()) {
      for (let index = 1; index < ring.length; index += 1) {
        const distance = getSegmentDistance([x, y], ring[index - 1]!, ring[index]!)

        if (distance < best) {
          best = distance
          nearest = entry
        }
      }
    }
  }

  return nearest
}

function getSegmentDistance(
  [x, y]: readonly [number, number],
  [x1 = 0, y1 = 0]: readonly number[],
  [x2 = 0, y2 = 0]: readonly number[],
) {
  const length = (x2 - x1) ** 2 + (y2 - y1) ** 2
  const t =
    length === 0
      ? 0
      : Math.max(0, Math.min(1, ((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / length))

  return Math.hypot(x - (x1 + t * (x2 - x1)), y - (y1 + t * (y2 - y1)))
}

function getPolygons(region: FogRegion) {
  return region.geometry.type === 'Polygon'
    ? [region.geometry.coordinates]
    : region.geometry.coordinates
}

/** West, south, east and north. */
function getBounds(region: FogRegion) {
  const bounds = [Infinity, Infinity, -Infinity, -Infinity]

  for (const [outer] of getPolygons(region)) {
    for (const [longitude = 0, latitude = 0] of outer ?? []) {
      bounds[0] = Math.min(bounds[0]!, longitude)
      bounds[1] = Math.min(bounds[1]!, latitude)
      bounds[2] = Math.max(bounds[2]!, longitude)
      bounds[3] = Math.max(bounds[3]!, latitude)
    }
  }

  return bounds as [number, number, number, number]
}

/** Inside one of the region's polygons: inside its outer ring and none of its holes. */
function containsPoint(region: FogRegion, point: readonly [number, number]) {
  return getPolygons(region).some(
    ([outer, ...holes]) =>
      outer !== undefined &&
      ringContains(outer, point) &&
      !holes.some((hole) => ringContains(hole, point)),
  )
}

/** Even-odd: whether a ray east from `point` crosses the ring an odd number of times. */
function ringContains(ring: Ring, [x, y]: readonly [number, number]) {
  let inside = false

  for (let index = 0, previous = ring.length - 1; index < ring.length; previous = index++) {
    const [x1 = 0, y1 = 0] = ring[index]!
    const [x2 = 0, y2 = 0] = ring[previous]!

    if (y1 > y !== y2 > y && x < ((x2 - x1) * (y - y1)) / (y2 - y1) + x1) {
      inside = !inside
    }
  }

  return inside
}
