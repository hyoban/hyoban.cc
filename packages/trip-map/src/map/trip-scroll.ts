import type { GeoJSONSource, Map as MapboxMap } from 'mapbox-gl'
import { getDistanceKm, type Coordinates } from '../geo.ts'
import { densify } from '../player/overview.ts'
import type { TripSummary } from '../player/types.ts'
import { getUnderLabels } from './basemap.ts'

// A trip's route drawn as far as its sidebar has been read: each Moment in it marks
// where it was (`[data-trip-map-stop-at="<longitude>,<latitude>"]`), placed along the
// route, and the line a little above the sidebar's middle stands for where the reader
// is, so scrolling down draws the route on to the next Moment's place and scrolling up
// takes it back. Between two Moments the route goes on as far as the first has been
// read past; after the last, to the route's end.

const SOURCE = 'trip-map-trip-scroll'
const LINE_LAYER = `${SOURCE}-line`
const STRAIGHT_LAYER = `${SOURCE}-straight`
const HEAD_LAYER = `${SOURCE}-head`
// How far down the sidebar the reader is taken to be reading, as a share of its height.
const READING_LINE = 0.4

type RoutePoint = {
  coordinates: Coordinates
  /** Along the route from its start, in kilometres; a gap between stretches adds none. */
  distance: number
  /** Which stretch of the route it's on. */
  segment: number
}

export type MeasuredRoute = {
  length: number
  points: RoutePoint[]
  /** Whether each stretch is a ferry or a flight, drawn dashed. */
  straight: boolean[]
}

/** A trip's route as one run of points, each with how far along the route it is. */
export function measureRoute(trip: Pick<TripSummary, 'segments'>): MeasuredRoute {
  const points: RoutePoint[] = []
  let distance = 0

  for (const [segment, { line, straight }] of trip.segments.entries()) {
    // Points along a flight, so its drawn part follows the line the overview draws.
    for (const [index, coordinates] of (straight ? densify(line) : line).entries()) {
      const previous = points.at(-1)

      if (index > 0 && previous) {
        distance += getDistanceKm(previous.coordinates, coordinates)
      }

      points.push({ coordinates, distance, segment })
    }
  }

  return { length: distance, points, straight: trip.segments.map(({ straight }) => straight) }
}

/**
 * How far along the route the point nearest `coordinates` is, no earlier than `from`:
 * a trip's places come in the order the route passes them, also where it comes back
 * the way it went.
 */
export function locateOnRoute(route: MeasuredRoute, coordinates: Coordinates, from = 0) {
  const [longitude, latitude] = coordinates
  // Near enough flat over the length of an edge: a degree of longitude, narrowed.
  const scale = Math.cos((latitude * Math.PI) / 180)
  let best = from
  let bestSquared = Infinity

  const consider = (distance: number, point: Coordinates) => {
    const dx = (point[0] - longitude) * scale
    const dy = point[1] - latitude
    const squared = dx * dx + dy * dy

    if (squared < bestSquared) {
      best = distance
      bestSquared = squared
    }
  }

  for (const [index, start] of route.points.entries()) {
    if (start.distance >= from) {
      consider(start.distance, start.coordinates)
    }

    const end = route.points[index + 1]

    if (!end || end.segment !== start.segment || end.distance <= from) {
      continue
    }

    const [x1, y1] = start.coordinates
    const [x2, y2] = end.coordinates
    const ex = (x2 - x1) * scale
    const ey = y2 - y1
    const squaredLength = ex * ex + ey * ey

    if (squaredLength === 0) {
      continue
    }

    const length = end.distance - start.distance
    // No earlier on the edge than `from`.
    const lowest = length > 0 ? Math.max(0, (from - start.distance) / length) : 0
    const t = Math.min(
      1,
      Math.max(lowest, ((longitude - x1) * scale * ex + (latitude - y1) * ey) / squaredLength),
    )

    consider(start.distance + length * t, [x1 + (x2 - x1) * t, y1 + (y2 - y1) * t])
  }

  return best
}

/** The route up to `distance`: each stretch's drawn part, and the point it has reached. */
export function sliceRoute(route: MeasuredRoute, distance: number) {
  const lines: { line: Coordinates[]; straight: boolean }[] = []
  let head: Coordinates | undefined

  for (const [index, point] of route.points.entries()) {
    const previous = route.points[index - 1]
    const sameSegment = previous?.segment === point.segment

    if (point.distance > distance) {
      // Partway along the edge it's reached.
      if (previous && sameSegment && previous.distance < distance) {
        const t = (distance - previous.distance) / (point.distance - previous.distance)
        const [x1, y1] = previous.coordinates
        const [x2, y2] = point.coordinates

        head = [x1 + (x2 - x1) * t, y1 + (y2 - y1) * t]
        lines.at(-1)?.line.push(head)
      }

      break
    }

    if (!sameSegment) {
      lines.push({ line: [], straight: route.straight[point.segment] ?? false })
    }

    lines.at(-1)!.line.push(point.coordinates)
    head = point.coordinates
  }

  return { head, lines: lines.filter(({ line }) => line.length > 1) }
}

/** The route's points from `from` to `to`, both ends included. */
export function getStretch(route: MeasuredRoute, from: number, to: number) {
  const { head: start } = sliceRoute(route, from)
  const { head: end } = sliceRoute(route, to)
  const between = route.points
    .filter(({ distance }) => distance > from && distance < to)
    .map(({ coordinates }) => coordinates)

  return [start, ...between, end].filter((point) => point !== undefined)
}

/**
 * Where along a run of marks (each at an offset, for a distance) `offset` falls: the
 * distance there, and the pair of marks it's between, by the first's index.
 */
export function interpolateMarks(
  marks: readonly { distance: number; offset: number }[],
  offset: number,
) {
  const last = Math.max(0, marks.length - 2)

  for (const [index, mark] of marks.entries()) {
    const next = marks[index + 1]

    if (offset <= mark.offset || !next) {
      return { distance: mark.distance, index: Math.min(Math.max(0, index - 1), last) }
    }

    if (offset < next.offset) {
      const span = next.offset - mark.offset
      const distance =
        span > 0
          ? mark.distance + ((next.distance - mark.distance) * (offset - mark.offset)) / span
          : next.distance

      return { distance, index }
    }
  }

  return { distance: 0, index: 0 }
}

export function createTripScroll({
  content,
  getColors,
  getMap,
  onDrawn,
  onStretch,
  signal,
}: {
  /** The trip sidebar's scrolling part. */
  content: HTMLElement
  getColors: () => { ink: string; paper: string }
  getMap: () => MapboxMap | undefined
  /** Some of the route is drawn, or none of it is again. */
  onDrawn: (drawn: boolean) => void
  /**
   * The reader came to another Moment: the way from its place to the next one's, to
   * bring into view. A Moment at the same place as the next keeps the view it has.
   */
  onStretch: (stretch: Coordinates[]) => void
  signal: AbortSignal
}) {
  let route: MeasuredRoute | undefined
  // The places of the Moments last placed along the route, and how far along each is.
  let placedKey: string | undefined
  let placed: number[] = []
  let drawn = false
  // The stretch last brought into view, by its ends.
  let stretchKey: string | undefined
  let frame = 0

  function readPlaces(entries: HTMLElement[]) {
    const key = entries.map((entry) => entry.dataset.tripMapStopAt).join(' ')

    if (key !== placedKey && route) {
      let from = 0

      placedKey = key
      placed = entries.map((entry) => {
        const [longitude, latitude] = entry.dataset.tripMapStopAt!.split(',').map(Number)

        from = locateOnRoute(route!, [longitude!, latitude!], from)
        return from
      })
    }

    return placed
  }

  /** How far along the route the reader is, and the Moments' places before and after. */
  function getPosition() {
    const entries = [...content.querySelectorAll<HTMLElement>('[data-trip-map-stop-at]')]
    const last = entries.at(-1)

    if (!route || !last) {
      return { distance: 0, from: 0, to: 0 }
    }

    const distances = readPlaces(entries)
    const top = content.getBoundingClientRect().top - content.scrollTop
    const readingLine = content.clientHeight * READING_LINE
    const end = last.getBoundingClientRect().bottom - top
    const maxScroll = content.scrollHeight - content.clientHeight
    // The last Moments may be too near the bottom to reach the reading line: the line
    // runs on faster, so the route is drawn whole at the bottom.
    const short = Math.max(0, end - (maxScroll + readingLine))
    const offset =
      content.scrollTop +
      readingLine +
      (maxScroll > 0 ? (short * content.scrollTop) / maxScroll : 0)
    const marks = [{ distance: 0, offset: readingLine }]

    for (const [index, entry] of entries.entries()) {
      marks.push({
        distance: distances[index]!,
        offset: Math.max(marks.at(-1)!.offset, entry.getBoundingClientRect().top - top),
      })
    }

    marks.push({ distance: route.length, offset: Math.max(marks.at(-1)!.offset, end) })

    const { distance, index } = interpolateMarks(marks, offset)

    return { distance, from: marks[index]!.distance, to: marks[index + 1]?.distance ?? distance }
  }

  function getSource() {
    return getMap()?.getSource<GeoJSONSource>(SOURCE)
  }

  function draw() {
    frame = 0

    const source = getSource()
    const { distance, from, to } = source ? getPosition() : { distance: 0, from: 0, to: 0 }
    const next = distance > 0

    if (source) {
      const { head, lines } = next && route ? sliceRoute(route, distance) : { lines: [] }

      source.setData({
        features: [
          ...lines.map(({ line, straight }) => ({
            geometry: { coordinates: line as [number, number][], type: 'LineString' as const },
            properties: { straight },
            type: 'Feature' as const,
          })),
          ...(head
            ? [
                {
                  geometry: { coordinates: head as [number, number], type: 'Point' as const },
                  properties: {},
                  type: 'Feature' as const,
                },
              ]
            : []),
        ],
        type: 'FeatureCollection',
      })
    }

    if (next !== drawn) {
      drawn = next
      stretchKey = undefined
      onDrawn(drawn)
    }

    // Under a kilometre: the same place, or as good as.
    const key = `${from}:${to}`

    if (drawn && route && key !== stretchKey && (to - from >= 1 || stretchKey === undefined)) {
      stretchKey = key
      onStretch(getStretch(route, from, to))
    }
  }

  function schedule() {
    if (route && !frame) {
      frame = requestAnimationFrame(draw)
    }
  }

  /** Adds the layers unless they're there already, also after the style changes. */
  function add() {
    const map = getMap()

    if (!map || map.getSource(SOURCE)) {
      return
    }

    const { ink, paper } = getColors()
    const { beforeId, slot } = getUnderLabels(map)
    const line = {
      'line-color': ink,
      'line-emissive-strength': 1,
      // The trip player's width, the route drawn as it draws it.
      'line-width': 3,
    }

    map.addSource(SOURCE, { data: { features: [], type: 'FeatureCollection' }, type: 'geojson' })
    map.addLayer(
      {
        filter: ['all', ['==', ['geometry-type'], 'LineString'], ['!', ['get', 'straight']]],
        id: LINE_LAYER,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: line,
        source: SOURCE,
        type: 'line',
        ...(slot && { slot }),
      },
      beforeId,
    )
    map.addLayer(
      {
        filter: ['all', ['==', ['geometry-type'], 'LineString'], ['get', 'straight']],
        id: STRAIGHT_LAYER,
        paint: { ...line, 'line-dasharray': [2, 2] },
        source: SOURCE,
        type: 'line',
        ...(slot && { slot }),
      },
      beforeId,
    )
    map.addLayer(
      {
        filter: ['==', ['geometry-type'], 'Point'],
        id: HEAD_LAYER,
        paint: {
          'circle-color': ink,
          'circle-emissive-strength': 1,
          'circle-radius': 6,
          'circle-stroke-color': paper,
          'circle-stroke-width': 2,
        },
        source: SOURCE,
        type: 'circle',
        ...(slot && { slot }),
      },
      beforeId,
    )
    schedule()
  }

  /** Follows the sidebar's scrolling for `trip`, drawing its route as far as it's read. */
  function start(trip: TripSummary) {
    route = measureRoute(trip)
    placedKey = undefined
    stretchKey = undefined
    schedule()
  }

  /** Takes the drawn route away. */
  function stop() {
    if (!route) {
      return
    }

    route = undefined
    cancelAnimationFrame(frame)
    frame = 0
    draw()
  }

  content.addEventListener('scroll', schedule, { passive: true, signal })
  window.addEventListener('resize', schedule, { passive: true, signal })
  signal.addEventListener('abort', () => cancelAnimationFrame(frame))

  return { add, start, stop }
}
