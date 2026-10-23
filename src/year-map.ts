import type { FogRegion, FogRegions, TravelMode, TripLeg } from '@hyoban/trip-map'

// The year review's drawings, as SVG path data worked out at build time: the cities under
// the year's footprint, those it lit, and each trip's route, in one flat projection fitted
// to what the year reached; and each trip on its own as a small glyph.

type Point = readonly [number, number]
type Ring = readonly (readonly number[])[]

export type YearMapCity = { d: string; id: string; name: string; weight: number }
/** A trip's route, a path per leg, so each can be drawn in from its start. */
export type YearMapRoute = { id: string; legs: { by: TravelMode; d: string }[] }
/** A leg of a trip as it went: by road or rail along its route, else straight. */
export type TripLine = { by: TravelMode; points: Point[] }

export type YearMap = {
  width: number
  height: number
  /** Every city in view, as one path of faint outlines. */
  outline: string
  lit: YearMapCity[]
  routes: YearMapRoute[]
  dots: { x: number; y: number }[]
}

/** The points a trip passed along: each leg's route, or a straight line where there's none. */
export function getTripLine(legs: readonly Pick<TripLeg, 'by' | 'from' | 'route' | 'to'>[]) {
  return legs.map((leg): TripLine => ({
    by: leg.by,
    points: leg.route
      ? leg.route.coordinates.map(([longitude, latitude]) => [longitude, latitude])
      : [
          [leg.from.longitude, leg.from.latitude],
          [leg.to.longitude, leg.to.latitude],
        ],
  }))
}

/**
 * The year on one map `width` units wide: the cities `lit` weighed by their Moments,
 * `routes` as lines of [longitude, latitude], and `places` as dots.
 */
export function buildYearMap(
  regions: FogRegions,
  lit: Readonly<Record<string, number>>,
  routes: readonly { id: string; lines: readonly TripLine[] }[],
  places: readonly Point[],
  width = 1000,
): YearMap {
  const cities = regions.features.filter((region) => !region.properties.fogged)
  const litCities = cities.filter((region) => lit[region.properties.id] !== undefined)
  const points = [
    ...litCities.flatMap((region) => getRings(region).flat() as unknown as Point[]),
    ...routes.flatMap((route) => route.lines.flatMap((line) => line.points)),
    ...places,
  ]
  const project = fit(points, width, 0.06)
  const [minX, minY, maxX, maxY] = project.lonLatBounds
  const inView = cities.filter((region) => {
    const [west, south, east, north] = getBounds(region)
    return east >= minX && west <= maxX && north >= minY && south <= maxY
  })
  const maxWeight = Math.max(1, ...Object.values(lit))

  return {
    width,
    height: project.height,
    outline: inView.map((region) => toRingsPath(getRings(region), project.point, 3)).join(''),
    lit: litCities.map((region) => ({
      d: toRingsPath(getRings(region), project.point),
      id: region.properties.id,
      name: region.properties.name,
      weight: Math.sqrt(lit[region.properties.id]! / maxWeight),
    })),
    routes: routes.map((route) => ({
      id: route.id,
      legs: route.lines
        .map((line) => ({
          by: line.by,
          d:
            line.by === 'plane'
              ? toArcPath(line.points.map(project.point))
              : toLinePath(line.points, project.point),
        }))
        .filter((leg) => leg.d),
    })),
    dots: dedupe(places.map(project.point)).map(([x, y]) => ({ x, y })),
  }
}

/**
 * A trip's route alone, fitted to a `size` square: its shape, as a glyph beside its name.
 * A trip flown to is drawn from where it landed to where it took off again, so the
 * getting there doesn't flatten it.
 */
export function buildRouteGlyph(lines: readonly TripLine[], size = 100) {
  const first = lines.findIndex((line) => line.by === 'plane')
  const last = lines.findLastIndex((line) => line.by === 'plane')
  const there = first >= 0 ? lines.slice(first + 1, first === last ? undefined : last) : lines
  const shown = (there.length > 0 ? there : lines).map((line) => line.points)
  const project = fit(shown.flat(), size, 0.1, size)
  return {
    d: shown.map((line) => toLinePath(line, project.point, 0.5)).join(''),
    width: size,
    height: project.height,
  }
}

/**
 * An equirectangular projection, its x squeezed by the latitude's cosine at the middle,
 * that fits `points` into `width` with `padding` of it around them; no taller than `maxHeight`.
 */
function fit(points: readonly Point[], width: number, padding: number, maxHeight = Infinity) {
  const xs = points.map(([x]) => x)
  const ys = points.map(([, y]) => y)
  let [west, east, south, north] = [
    Math.min(...xs),
    Math.max(...xs),
    Math.min(...ys),
    Math.max(...ys),
  ]
  if (!Number.isFinite(west)) [west, east, south, north] = [73, 135, 18, 54]
  const squeeze = Math.cos((((south + north) / 2) * Math.PI) / 180)
  const spanX = Math.max((east - west) * squeeze, 0.05)
  const spanY = Math.max(north - south, 0.05)
  const inner = width * (1 - padding * 2)
  const scale = Math.min(inner / spanX, (maxHeight - width * padding * 2) / spanY)
  const height = Math.min(Math.round(spanY * scale + width * padding * 2), maxHeight)
  const offsetX = (width - spanX * scale) / 2
  const offsetY = (height - spanY * scale) / 2
  const marginX = offsetX / scale / squeeze
  const marginY = offsetY / scale

  return {
    height,
    lonLatBounds: [west - marginX, south - marginY, east + marginX, north + marginY] as const,
    // Whole units on a map, tenths on a glyph: finer than either is drawn at.
    point: ([longitude, latitude]: Point): Point => [
      round(offsetX + (longitude - west) * squeeze * scale, width >= 500 ? 1 : 10),
      round(offsetY + (north - latitude) * scale, width >= 500 ? 1 : 10),
    ],
  }
}

function getRings(region: FogRegion): Ring[] {
  return region.geometry.type === 'Polygon'
    ? [...region.geometry.coordinates]
    : region.geometry.coordinates.flat()
}

function getBounds(region: FogRegion) {
  const points = getRings(region).flat()
  const xs = points.map((point) => point[0]!)
  const ys = points.map((point) => point[1]!)
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)] as const
}

function toRingsPath(rings: Ring[], project: (point: Point) => Point, tolerance = 1.5) {
  return rings
    .map((ring) => {
      const points = simplify(
        ring.map((point) => project(point as unknown as Point)),
        tolerance,
      )
      return points.length > 2 ? `${toPath(points)}Z` : ''
    })
    .join('')
}

function toLinePath(line: readonly Point[], project: (point: Point) => Point, tolerance = 1) {
  const points = simplify(line.map(project), tolerance)
  return points.length > 1 ? toPath(points) : ''
}

/** A flight from its first point to its last, bowed to one side as flights are drawn. */
function toArcPath(points: readonly Point[]) {
  const [start, end] = [points[0]!, points.at(-1)!]
  const [dx, dy] = [end[0] - start[0], end[1] - start[1]]
  const control = [
    round((start[0] + end[0]) / 2 + dy * 0.2, 1),
    round((start[1] + end[1]) / 2 - dx * 0.2, 1),
  ]
  return `M${start[0]} ${start[1]}Q${control[0]} ${control[1]} ${end[0]} ${end[1]}`
}

function toPath(points: readonly Point[]) {
  return points.map(([x, y], index) => `${index === 0 ? 'M' : 'L'}${x} ${y}`).join('')
}

/** Drops each point closer than `tolerance` to the last one kept, keeping the ends. */
function simplify(points: readonly Point[], tolerance: number) {
  const kept: Point[] = []
  for (const [index, point] of points.entries()) {
    const last = kept.at(-1)
    if (
      !last ||
      index === points.length - 1 ||
      Math.hypot(point[0] - last[0], point[1] - last[1]) >= tolerance
    ) {
      kept.push(point)
    }
  }
  return kept
}

function dedupe(points: readonly Point[]) {
  return [...new Map(points.map((point) => [`${point[0]},${point[1]}`, point])).values()]
}

function round(value: number, factor: number) {
  return Math.round(value * factor) / factor
}
