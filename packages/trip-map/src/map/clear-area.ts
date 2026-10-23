import mapboxgl, { type LngLatLike, type Map as MapboxMap, type PaddingOptions } from 'mapbox-gl'

// What the map leaves clear: its box less the panels, the toolbar and the margins
// around them, as `padding`, and within it a narrower part where what's shown sits
// comfortably, off its edges. A move for something the viewer is shown goes only as far
// as it takes to have it there: none if it already is, a pan if it fits at the zoom the
// map is at, and zoomed out just enough if it doesn't. So what's in view but hard by an
// edge is nudged in a little. Only something nowhere in view is flown to anew.

type Point = { x: number; y: number }
type Box = { bottom: number; left: number; right: number; top: number }

// A route already in view fills at least this much of the clear area, across or down,
// to stay as it is: smaller, it's framed anew.
const MIN_FILL = 0.3
// How far in from each edge of the clear area its comfortable part starts, of its size.
const COMFORT_INSET = 0.1

export type ClearAreaPlan =
  /** Every point sits comfortably in the clear area. `fill` is how much of it their spread takes. */
  | { fill: number; kind: 'stay' }
  /** Some are out or by an edge, and they fit at this zoom: moved by `dx`, `dy` pixels. */
  | { dx: number; dy: number; fill: number; kind: 'pan' }
  /** Some are out or by an edge, and they don't fit at this zoom. */
  | { kind: 'zoom-out' }
  /** None is in the clear area. */
  | { kind: 'away' }

/** The comfortable part of the clear area `box`. */
export function getComfortBox(box: Box): Box {
  const x = (box.right - box.left) * COMFORT_INSET
  const y = (box.bottom - box.top) * COMFORT_INSET

  return { bottom: box.bottom - y, left: box.left + x, right: box.right - x, top: box.top + y }
}

/** How far `points`, on screen, are from sitting comfortably in `box`, and what it takes to have them there. */
export function planClearArea(points: readonly Point[], box: Box): ClearAreaPlan {
  const comfort = getComfortBox(box)
  const xs = points.map((point) => point.x)
  const ys = points.map((point) => point.y)
  const left = Math.min(...xs)
  const right = Math.max(...xs)
  const top = Math.min(...ys)
  const bottom = Math.max(...ys)
  const fill = Math.max(
    (right - left) / (box.right - box.left),
    (bottom - top) / (box.bottom - box.top),
  )
  const isIn = (within: Box) => (point: Point) =>
    point.x >= within.left &&
    point.x <= within.right &&
    point.y >= within.top &&
    point.y <= within.bottom

  if (points.every(isIn(comfort))) {
    return { fill, kind: 'stay' }
  }

  if (!points.some(isIn(box))) {
    return { kind: 'away' }
  }

  if (right - left > comfort.right - comfort.left || bottom - top > comfort.bottom - comfort.top) {
    return { kind: 'zoom-out' }
  }

  const reach = (low: number, high: number, from: number, to: number) =>
    low < from ? low - from : high > to ? high - to : 0

  return {
    dx: reach(left, right, comfort.left, comfort.right),
    dy: reach(top, bottom, comfort.top, comfort.bottom),
    fill,
    kind: 'pan',
  }
}

/** The clear area of `map` that `padding` leaves, in its pixels. */
function getClearBox(map: MapboxMap, padding: Required<PaddingOptions>): Box {
  const container = map.getContainer()

  return {
    bottom: container.clientHeight - padding.bottom,
    left: padding.left,
    right: container.clientWidth - padding.right,
    top: padding.top,
  }
}

/** Where `coordinates` are on `map` against the part `padding` leaves clear. */
export function planOnMap(
  map: MapboxMap,
  coordinates: readonly LngLatLike[],
  padding: Required<PaddingOptions>,
) {
  return planClearArea(
    coordinates.map((each) => map.project(each)),
    getClearBox(map, padding),
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
 * Brings `coordinates` into the comfortable part of what `padding` leaves of `map`, as
 * little as it takes: a pan if they fit at this zoom, else zoomed out just enough,
 * never closer. `plan` is where they are now. Reports whether the map moved.
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

  const container = map.getContainer()
  const comfort = getComfortBox(getClearBox(map, padding))

  map.fitBounds(bounds, {
    duration,
    maxZoom: map.getZoom(),
    padding: {
      bottom: container.clientHeight - comfort.bottom,
      left: comfort.left,
      right: container.clientWidth - comfort.right,
      top: comfort.top,
    },
    pitch: map.getPitch(),
  })
  return true
}
