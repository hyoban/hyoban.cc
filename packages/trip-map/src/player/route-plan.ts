import type { Coordinates } from '../geo.ts'
import type { TravelMode } from '../trips.ts'
import { getRouteLine, getTravelMode } from './route.ts'
import { buildSlides, getStopPointEnds, getWaypointsBefore, type TripSlide } from './slides.ts'
import type { TripData, TripDataStop, TripDataWaypoint, TripPlace, TripPoint } from './types.ts'

// The route a trip is played along, worked out once from its data: every place in
// visiting order (each stop's waypoints, then its places), where each stop's points
// are in that list, and the slides. Every index into the route is answered here, so
// the player never does the arithmetic itself.

export type RoutePlan = ReturnType<typeof createRoutePlan>

export function createRoutePlan(trip: TripData) {
  // A waypoint has its own day and time; a stop's places take the stop's.
  const toPoint = (
    place: TripPlace,
    when: Pick<TripDataWaypoint, 'dateKey' | 'day' | 'time'>,
    stopIndex: number,
    waypoint: boolean,
  ): TripPoint => ({
    coordinates: [place.longitude, place.latitude],
    dateKey: when.dateKey,
    day: when.day,
    name: place.name,
    stopIndex,
    time: when.time,
    waypoint,
  })
  const points: TripPoint[] = [
    ...trip.stops.flatMap((stop, stopIndex) => [
      ...stop.waypoints.map((waypoint) => toPoint(waypoint, waypoint, stopIndex, true)),
      ...stop.places.map((place) => toPoint(place, stop, stopIndex, false)),
    ]),
    ...trip.trailingWaypoints.map((waypoint) =>
      toPoint(waypoint, waypoint, trip.stops.length, true),
    ),
  ]
  // Index into `points` just past each stop's last place.
  const stopPointEnds = getStopPointEnds(trip.stops)
  const slides: TripSlide<TripDataStop>[] = buildSlides(trip.stops)
  const legLines = trip.legs.map((leg) => leg.line)
  const legModes = trip.legs.map((leg) => leg.mode)

  /** Index of a stop's first route point: its first waypoint, or else its first place. */
  function pointStart(stopIndex: number) {
    return stopIndex === 0 ? 0 : stopPointEnds[stopIndex - 1]!
  }

  /** Index of a stop's first place, after the waypoints leading to it. */
  function placeStart(stopIndex: number) {
    return pointStart(stopIndex) + (trip.stops[stopIndex]?.waypoints.length ?? 0)
  }

  /** Index of the last point a stop's card drives on to: the next place, or the route's end. */
  function onwardEnd(stopIndex: number) {
    return Math.min(stopPointEnds[stopIndex]!, points.length - 1)
  }

  return {
    legs: trip.legs,
    points,
    slides,
    stopPointEnds,
    /** The line from `points[from]` to `points[to - 1]`, along roads where a route is known. */
    line(from: number, to: number) {
      return getRouteLine(points, legLines, from, to)
    },
    onwardEnd,
    placeStart,
    pointStart,
    /** How the segment from `start` to `end` is travelled. */
    travelMode(start: Coordinates, end: Coordinates): TravelMode {
      return getTravelMode(points, legLines, legModes, start, end)
    },
    /** The waypoints the map passes before a slide shows, as `[first point, count]`. */
    waypointsBefore(slide: TripSlide<TripDataStop>) {
      return getWaypointsBefore(slide, trip.stops, stopPointEnds, trip.trailingWaypoints.length)
    },
  }
}
