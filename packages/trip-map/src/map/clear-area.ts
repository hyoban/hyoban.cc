import mapboxgl, { type LngLatLike, type Map as MapboxMap, type PaddingOptions } from 'mapbox-gl'

// What the map leaves clear: its box less the panels, the toolbar and the margins
// around them, as `padding`. A move for something the viewer is shown goes only as far
// as it takes to have it there: none if it already is, a pan if it fits at the zoom the
// map is at, and zoomed out just enough if it doesn't. Only something nowhere in view
// is flown to anew.

type Point = { x: number; y: number }
type Box = { bottom: number; left: number; right: number; top: number }

// A route already in view fills at least this much of the clear area, across or down,
// to stay as it is: smaller, it's framed anew.
const MIN_FILL = 0.3

export type ClearAreaPlan =
  /** Every point is in the clear area. `fill` is how much of it their spread takes. */
  | { fill: number; kind: 'stay' }
  /** Some are out, and they fit at this zoom: moved by `dx`, `dy` pixels. */
  | { dx: number; dy: number; fill: number; kind: 'pan' }
  /** Some are out, and they don't fit at this zoom. */
  | { kind: 'zoom-out' }
  /** None is in the clear area. */
  | { kind: 'away' }

/** How far `points`, on screen, are from being in `box`, and what it takes to have them there. */
export function planClearArea(points: readonly Point[], box: Box): ClearAreaPlan {
  const xs = points.map((point) => point.x)
  const ys = points.map((point) => point.y)
  const left = Math.min(...xs)
  const right = Math.max(...xs)
  const top = Math.min(...ys)
  const bottom = Math.max(...ys)
  const width = box.right - box.left
  const height = box.bottom - box.top
  const fill = Math.max((right - left) / width, (bottom - top) / height)
  const isIn = (point: Point) =>
    point.x >= box.left && point.x <= box.right && point.y >= box.top && point.y <= box.bottom

  if (points.every(isIn)) {
    return { fill, kind: 'stay' }
  }

  if (!points.some(isIn)) {
    return { kind: 'away' }
  }

  if (right - left > width || bottom - top > height) {
    return { kind: 'zoom-out' }
  }

  const reach = (low: number, high: number, from: number, to: number) =>
    low < from ? low - from : high > to ? high - to : 0

  return {
    dx: reach(left, right, box.left, box.right),
    dy: reach(top, bottom, box.top, box.bottom),
    fill,
    kind: 'pan',
  }
}

/** Where `coordinates` are on `map` against the part `padding` leaves clear. */
export function planOnMap(
  map: MapboxMap,
  coordinates: readonly LngLatLike[],
  padding: Required<PaddingOptions>,
) {
  const container = map.getContainer()

  return planClearArea(
    coordinates.map((each) => map.project(each)),
    {
      bottom: container.clientHeight - padding.bottom,
      left: padding.left,
      right: container.clientWidth - padding.right,
      top: padding.top,
    },
  )
}

/**
 * Whether a route needs flying to anew: not in the clear area at all, or too small
 * there to make out. Otherwise it's left as it is or brought in as little as it takes.
 */
export function needsFraming(plan: ClearAreaPlan) {
  return (
    plan.kind === 'away' || ((plan.kind === 'stay' || plan.kind === 'pan') && plan.fill < MIN_FILL)
  )
}

/**
 * Brings `coordinates` into the part of `map` that `padding` leaves clear, as little as
 * it takes: a pan if they fit at this zoom, else zoomed out just enough, never closer.
 * `plan` is where they are now. Reports whether the map moved.
 */
export function bringIntoClearArea(
  map: MapboxMap,
  coordinates: readonly LngLatLike[],
  padding: Required<PaddingOptions>,
  plan: ClearAreaPlan,
  duration: number,
) {
  if (plan.kind === 'stay') {
    return false
  }

  if (plan.kind === 'pan') {
    map.panBy([plan.dx, plan.dy], { duration })
    return true
  }

  const bounds = new mapboxgl.LngLatBounds()

  for (const each of coordinates) {
    bounds.extend(each)
  }

  map.fitBounds(bounds, { duration, maxZoom: map.getZoom(), padding, pitch: map.getPitch() })
  return true
}
