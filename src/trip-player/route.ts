import type { Coordinates } from '../geo'

// The trip's route as a line: built from its places and the road geometry between
// them, measured, and walked along to find where a moving tip is. DOM-free.

export type TravelMode = 'car' | 'ferry' | 'plane'

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
 * Measures in plain degrees: only the share of the whole matters, and it keeps the
 * tip's speed even along the line.
 */
export function measureLine(line: readonly Coordinates[]): MeasuredLine {
  const lengths = line
    .slice(1)
    .map((coordinates, index) =>
      Math.hypot(coordinates[0] - line[index]![0], coordinates[1] - line[index]![1]),
    )

  return { lengths, line, total: lengths.reduce((total, length) => total + length, 0) }
}

/**
 * The part of a measured line up to `fraction` of its length: the vertices passed
 * after the first, then the tip. Also returns the segment the tip is on.
 */
export function walkLine({ lengths, line, total }: MeasuredLine, fraction: number) {
  let remaining = Math.min(Math.max(fraction, 0), 1) * total
  let segment = 0

  while (segment < lengths.length - 1 && remaining > lengths[segment]!) {
    remaining -= lengths[segment]!
    segment += 1
  }

  const start = line[segment]!
  const end = line[segment + 1] ?? start
  const ratio = lengths[segment] ? remaining / lengths[segment]! : 1
  const tip: [number, number] = [
    start[0] + (end[0] - start[0]) * ratio,
    start[1] + (end[1] - start[1]) * ratio,
  ]

  return {
    end,
    passed: [...line.slice(1, segment + 1).map(([x, y]) => [x, y] as [number, number]), tip],
    start,
  }
}

/**
 * How the segment from `start` to `end` is travelled: a ferry or plane leg is drawn
 * as one straight segment between its two places; everything else is by car.
 */
export function getTravelMode(
  points: readonly RoutePoint[],
  legModes: readonly TravelMode[],
  start: Coordinates,
  end: Coordinates,
): TravelMode {
  const same = (first: Coordinates, second: Coordinates) =>
    first[0] === second[0] && first[1] === second[1]

  return (
    legModes.find(
      (mode, legIndex) =>
        mode !== 'car' &&
        same(points[legIndex]!.coordinates, start) &&
        same(points[legIndex + 1]!.coordinates, end),
    ) ?? 'car'
  )
}

export function lineFeature(coordinates: readonly Coordinates[]) {
  return {
    geometry: { coordinates: coordinates as [number, number][], type: 'LineString' as const },
    properties: {},
    type: 'Feature' as const,
  }
}
