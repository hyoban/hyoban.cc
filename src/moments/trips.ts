import type { MapLocation } from './map-locations.ts'

/**
 * A place the route passes through without a Moment there. `at` is a date or an
 * ISO timestamp with offset; a waypoint without `at` follows the waypoint before it.
 */
export type TripWaypointDefinition = Readonly<{
  at?: string
  location: string
}>

export type TripDefinition = Readonly<{
  end: string
  id: string
  start: string
  title: string
  waypoints?: readonly TripWaypointDefinition[]
}>

type TripLocation = MapLocation & { id: string }

type TripMoment = Readonly<{
  dateKey: string
  locations: readonly TripLocation[]
  occurredAt: string
}>

export type TripWaypoint = Readonly<{
  dateKey: string
  location: TripLocation
  /** `HH:mm` when the waypoint has its own timestamp. */
  time?: string
}>

export type TripStop<T extends TripMoment> = T & {
  /** Waypoints passed on the way from the previous stop to this one. */
  waypoints: TripWaypoint[]
}

export type Trip<T extends TripMoment> = TripDefinition &
  Readonly<{
    dayCount: number
    distanceKm: number
    placeCount: number
    stops: TripStop<T>[]
    /** Waypoints after the last stop. */
    trailingWaypoints: TripWaypoint[]
  }>

const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})$/
const TRIP_ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const EARTH_RADIUS_KM = 6371
const DAY_MS = 86_400_000

export function defineTrips<const T extends readonly TripDefinition[]>(trips: T) {
  const ids = new Set<string>()

  for (const trip of trips) {
    if (!TRIP_ID_PATTERN.test(trip.id)) {
      throw new Error(`Invalid trip id "${trip.id}".`)
    }

    if (ids.has(trip.id)) {
      throw new Error(`Duplicate trip id "${trip.id}".`)
    }

    ids.add(trip.id)

    if (!isDateKey(trip.start) || !isDateKey(trip.end) || trip.start > trip.end) {
      throw new RangeError(`Invalid date range for trip "${trip.id}".`)
    }

    let previousAt: string | undefined

    for (const waypoint of trip.waypoints ?? []) {
      if (waypoint.at === undefined) {
        if (previousAt === undefined) {
          throw new Error(`The first waypoint of trip "${trip.id}" needs an "at" time.`)
        }

        continue
      }

      const dateKey = waypoint.at.slice(0, 10)
      const validAt =
        isDateKey(waypoint.at) ||
        (TIMESTAMP_PATTERN.test(waypoint.at) && !Number.isNaN(Date.parse(waypoint.at)))

      if (!validAt || dateKey < trip.start || dateKey > trip.end) {
        throw new RangeError(`Invalid waypoint time "${waypoint.at}" in trip "${trip.id}".`)
      }

      if (previousAt !== undefined && compareTimes(previousAt, waypoint.at) > 0) {
        throw new RangeError(`Waypoints of trip "${trip.id}" must be in time order.`)
      }

      previousAt = waypoint.at
    }
  }

  return trips
}

/**
 * Collects the located moments inside a trip's date range and threads the trip's
 * waypoints between them by time. `moments` must be sorted oldest first.
 */
export function buildTrip<T extends TripMoment>(
  trip: TripDefinition,
  moments: T[],
  resolveLocation: (id: string) => TripLocation,
): Trip<T> {
  const waypoints: (TripWaypoint & { at: string })[] = []

  for (const waypoint of trip.waypoints ?? []) {
    const at = waypoint.at ?? waypoints.at(-1)!.at

    waypoints.push({
      at,
      dateKey: at.slice(0, 10),
      location: resolveLocation(waypoint.location),
      ...(waypoint.at && waypoint.at.length > 10 ? { time: waypoint.at.slice(11, 16) } : {}),
    })
  }

  let waypointIndex = 0
  const stops = moments
    .filter(
      (moment) =>
        moment.locations.length > 0 && moment.dateKey >= trip.start && moment.dateKey <= trip.end,
    )
    .map((moment): TripStop<T> => {
      const before: TripWaypoint[] = []

      // On the same day, a waypoint comes before a Moment that has no time.
      while (
        waypointIndex < waypoints.length &&
        (moment.occurredAt.length <= 10
          ? waypoints[waypointIndex]!.at.slice(0, 10) <= moment.dateKey
          : compareTimes(waypoints[waypointIndex]!.at, moment.occurredAt) <= 0)
      ) {
        const { at: _at, ...waypoint } = waypoints[waypointIndex]!
        before.push(waypoint)
        waypointIndex += 1
      }

      return { ...moment, waypoints: before }
    })
  const trailingWaypoints = waypoints
    .slice(waypointIndex)
    .map(({ at: _at, ...waypoint }) => waypoint)
  const path = [
    ...stops.flatMap((stop) => [
      ...stop.waypoints.map((waypoint) => waypoint.location),
      ...stop.locations,
    ]),
    ...trailingWaypoints.map((waypoint) => waypoint.location),
  ]
  let distanceKm = 0

  for (let index = 1; index < path.length; index += 1) {
    distanceKm += getDistanceKm(path[index - 1]!, path[index]!)
  }

  return {
    ...trip,
    dayCount: getDayOffset(trip.start, trip.end) + 1,
    distanceKm: Math.round(distanceKm),
    placeCount: new Set(path.map((location) => location.id)).size,
    stops,
    trailingWaypoints,
  }
}

/** Great-circle distance, so it is a lower bound for the distance actually travelled. */
export function getDistanceKm(from: MapLocation, to: MapLocation) {
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180
  const latitudeDelta = toRadians(to.latitude - from.latitude)
  const longitudeDelta = toRadians(to.longitude - from.longitude)
  const a =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(toRadians(from.latitude)) *
      Math.cos(toRadians(to.latitude)) *
      Math.sin(longitudeDelta / 2) ** 2

  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a))
}

export function getDayOffset(start: string, dateKey: string) {
  return Math.round(
    (Date.parse(`${dateKey}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / DAY_MS,
  )
}

/** Orders dates and timestamps; a date sorts before any time on that day. */
function compareTimes(first: string, second: string) {
  const dateComparison = first.slice(0, 10).localeCompare(second.slice(0, 10))

  if (dateComparison !== 0 || first.length <= 10 || second.length <= 10) {
    return dateComparison || first.length - second.length
  }

  return Date.parse(first) - Date.parse(second)
}

function isDateKey(value: string) {
  return DATE_KEY_PATTERN.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
}
