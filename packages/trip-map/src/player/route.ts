import { EARTH_RADIUS_KM, getDistanceKm, type Coordinates } from '../geo.ts'
import type { TravelMode } from '../trips.ts'

// The trip's route as a line: built from its places and the road geometry between
// them, measured, and walked along to find where a moving tip is. DOM-free.

export type { TravelMode }

type RoutePoint = Readonly<{ coordinates: Coordinates }>

/** A line with each segment's length, ready to be walked along. */
export type MeasuredLine = Readonly<{
  lengths: readonly number[]
  line: readonly Coordinates[]
  total: number
}>

/**
 * The line from `points[from]` to `points[to - 1]`, following `legs` (the road between
 * each pair of consecutive points) where one is known and straight otherwise.
 */
export function getRouteLine(
  points: readonly RoutePoint[],
  legs: readonly (readonly Coordinates[] | null)[],
  from: number,
  to: number,
) {
  const line: [number, number][] = []

  for (let index = from; index < to; index += 1) {
    if (index > from) {
      for (const coordinates of legs[index - 1] ?? []) {
        line.push([coordinates[0], coordinates[1]])
      }
    }

    const [longitude, latitude] = points[index]!.coordinates
    line.push([longitude, latitude])
  }

  return line
}

/**
 * Measures as the map draws it, in Mercator units: only the share of the whole
 * matters, and this keeps the tip's speed even on screen, where a degree of latitude
 * is drawn taller than one of longitude away from the equator.
 */
export function measureLine(line: readonly Coordinates[]): MeasuredLine {
  const projected = line.map(toMercator)
  const lengths = projected
    .slice(1)
    .map((point, index) =>
      Math.hypot(point[0] - projected[index]![0], point[1] - projected[index]![1]),
    )

  return { lengths, line, total: lengths.reduce((total, length) => total + length, 0) }
}

// Latitudes nearer the poles than this are drawn as if at it, as Web Mercator does.
const MAX_LATITUDE = 85.051129

/** Mercator, scaled so a degree of longitude stays a unit. */
function toMercator([longitude, latitude]: Coordinates): [number, number] {
  const clamped = Math.min(Math.max(latitude, -MAX_LATITUDE), MAX_LATITUDE)
  const radians = (clamped * Math.PI) / 180

  return [longitude, (Math.log(Math.tan(Math.PI / 4 + radians / 2)) * 180) / Math.PI]
}

/**
 * The part of a measured line up to `fraction` of its length: the vertices passed
 * after the first, then the tip. Also returns the segment the tip is on, by its index
 * and ends, and how far along it the tip is.
 */
export function walkLine({ lengths, line, total }: MeasuredLine, fraction: number) {
  const share = Math.min(Math.max(fraction, 0), 1)
  let remaining = share * total
  let segment = 0

  while (segment < lengths.length - 1 && remaining > lengths[segment]!) {
    remaining -= lengths[segment]!
    segment += 1
  }

  // The whole line walked lands on its last point exactly, whatever rounding did.
  if (share >= 1) {
    segment = Math.max(lengths.length - 1, 0)
  }

  const start = line[segment]!
  const end = line[segment + 1] ?? start
  const ratio = share >= 1 || !lengths[segment] ? 1 : remaining / lengths[segment]!
  const tip: [number, number] = [
    start[0] + (end[0] - start[0]) * ratio,
    start[1] + (end[1] - start[1]) * ratio,
  ]

  return {
    end,
    passed: [...line.slice(1, segment + 1).map(([x, y]) => [x, y] as [number, number]), tip],
    ratio,
    segment,
    start,
  }
}

/**
 * How the segment from `start` to `end` is travelled: the mode of the ferry, plane or
 * train leg it lies on, whether that leg is one straight segment between its two
 * places or runs along a rail line; everything else is by car.
 */
export function getTravelMode(
  points: readonly RoutePoint[],
  legs: readonly (readonly Coordinates[] | null)[],
  legModes: readonly TravelMode[],
  start: Coordinates,
  end: Coordinates,
): TravelMode {
  const same = (first: Coordinates, second: Coordinates) =>
    first[0] === second[0] && first[1] === second[1]

  return (
    legModes.find((mode, legIndex) => {
      if (mode === 'car') {
        return false
      }

      const line = [
        points[legIndex]!.coordinates,
        ...(legs[legIndex] ?? []),
        points[legIndex + 1]!.coordinates,
      ]

      return line.some(
        (coordinates, index) =>
          index > 0 && same(line[index - 1]!, start) && same(coordinates, end),
      )
    }) ?? 'car'
  )
}

/** Starts and ends at rest, so speed never jumps between one stretch and the next. */
export function easeInOut(t: number) {
  return (1 - Math.cos(Math.PI * Math.min(Math.max(t, 0), 1))) / 2
}

export function lineFeature(coordinates: readonly Coordinates[]) {
  return {
    geometry: { coordinates: coordinates as [number, number][], type: 'LineString' as const },
    properties: {},
    type: 'Feature' as const,
  }
}

// A router reaches a place on the far side of a divided road by driving on to the next
// turning and coming back: the leg passes within this many metres of its end, runs on and
// returns. Such a spur, at either end, is cut there; one only a little longer than twice
// as far as it reaches is a road driven out and back, where a loop round a block is not.
const TURN_BACK_METRES = 300
const TURN_BACK_MIN_METRES = 4 * TURN_BACK_METRES
const TURN_BACK_RATIO = 2.6

/** How long a line is, in kilometres. */
export function getLineKm(line: readonly Coordinates[]) {
  let km = 0

  for (let index = 1; index < line.length; index += 1) {
    km += getDistanceKm(line[index - 1]!, line[index]!)
  }

  return km
}

/**
 * A routed leg without the spurs a router drives out and back at its ends to reach a place
 * from the far side of the road (see TURN_BACK_METRES): the line instead stops where it
 * first passes the place and steps across to it. A leg between places this close keeps
 * its line.
 */
export function trimTurnBacks(line: readonly Coordinates[]): Coordinates[] {
  if (line.length < 3 || getDistanceKm(line[0]!, line.at(-1)!) * 1000 < 2 * TURN_BACK_METRES) {
    return [...line]
  }

  return trimEnd(trimEnd(line).reverse()).reverse()
}

/** `line` with an out-and-back spur at its end cut where it first passes its end. */
function trimEnd(line: readonly Coordinates[]): Coordinates[] {
  const end = line.at(-1)!
  // Metres east and north of the end, near enough flat over a spur's few kilometres.
  const metres = 1000 * ((Math.PI / 180) * EARTH_RADIUS_KM)
  const scale = Math.cos((end[1] * Math.PI) / 180)
  const local = line.map(
    ([x, y]) => [(x - end[0]) * scale * metres, (y - end[1]) * metres] as const,
  )

  for (let index = 0; index < local.length - 2; index += 1) {
    const [ax, ay] = local[index]!
    const [bx, by] = local[index + 1]!
    const length = (bx - ax) ** 2 + (by - ay) ** 2
    const t = length ? Math.min(Math.max(-(ax * (bx - ax) + ay * (by - ay)) / length, 0), 1) : 0
    const passing = [ax + t * (bx - ax), ay + t * (by - ay)] as const

    if (Math.hypot(...passing) >= TURN_BACK_METRES) {
      continue
    }

    // The spur: from where it passes on to the end.
    const spur = [passing, ...local.slice(index + 1)]
    let spurLength = 0
    let reach = 0

    for (let point = 1; point < spur.length; point += 1) {
      spurLength += Math.hypot(
        spur[point]![0] - spur[point - 1]![0],
        spur[point]![1] - spur[point - 1]![1],
      )
      reach = Math.max(
        reach,
        Math.hypot(spur[point]![0] - passing[0], spur[point]![1] - passing[1]),
      )
    }

    if (spurLength < TURN_BACK_MIN_METRES || spurLength > TURN_BACK_RATIO * reach) {
      return [...line]
    }

    const [first, second] = [line[index]!, line[index + 1]!]
    const cut: Coordinates = [
      first[0] + t * (second[0] - first[0]),
      first[1] + t * (second[1] - first[1]),
    ]
    return [...line.slice(0, index + 1), cut, end]
  }

  return [...line]
}

/**
 * Drops the points that stray less than `tolerance` (in degrees) from the line through
 * their neighbours (Douglas–Peucker), keeping both ends.
 */
export function simplifyLine(line: readonly Coordinates[], tolerance: number) {
  if (line.length < 3) {
    return [...line]
  }

  const keep = new Set([0, line.length - 1])
  const spans: [number, number][] = [[0, line.length - 1]]

  while (spans.length > 0) {
    const [first, last] = spans.pop()!
    const [x1, y1] = line[first]!
    const [x2, y2] = line[last]!
    const length = Math.hypot(x2 - x1, y2 - y1)
    let farthest = -1
    let farthestDistance = tolerance

    for (let index = first + 1; index < last; index += 1) {
      const [x, y] = line[index]!
      const distance = length
        ? Math.abs((x2 - x1) * (y1 - y) - (x1 - x) * (y2 - y1)) / length
        : Math.hypot(x - x1, y - y1)

      if (distance > farthestDistance) {
        farthest = index
        farthestDistance = distance
      }
    }

    if (farthest !== -1) {
      keep.add(farthest)
      spans.push([first, farthest], [farthest, last])
    }
  }

  return line.filter((_, index) => keep.has(index))
}
