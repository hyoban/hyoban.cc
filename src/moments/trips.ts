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
  /** Photos for the intro and summary, as `<moment id>/<file>`, best first. */
  covers?: readonly string[]
  end: string
  id: string
  /** Default background music, an id from src/data/music.ts. */
  music?: string
  start: string
  /** Location id pairs travelled by ferry or plane; drawn straight instead of along roads. */
  straightLegs?: readonly (readonly [string, string])[]
  title: string
  waypoints?: readonly TripWaypointDefinition[]
}>

type TripLocation = MapLocation & { id: string }

/** A driving route between two places, cached in src/data/trip-routes.json. */
export type TripRoute = Readonly<{
  coordinates: readonly (readonly [number, number])[]
  /** Metres along the route. */
  distance: number
  /** Seconds of driving without traffic, as Mapbox estimates it. */
  duration?: number
}>

export type TripLeg = Readonly<{
  from: TripLocation
  route?: TripRoute
  straight: boolean
  to: TripLocation
}>

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
    /** Hours of driving along cached routes; ferries and flights aren't counted. */
    drivingHours: number
    placeCount: number
    /** One leg between each pair of consecutive places in `path`. */
    legs: TripLeg[]
    /** Every place in visiting order: each stop's waypoints, then its locations. */
    path: TripLocation[]
    stops: TripStop<T>[]
    /** Waypoints after the last stop. */
    trailingWaypoints: TripWaypoint[]
  }>

const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})$/
const TRIP_ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const COVER_PATTERN =
  /^\d{4}\/\d{2}\/\d{2}-\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*\/[a-z0-9]+(?:-[a-z0-9]+)*\.[a-z0-9]+$/
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

    for (const cover of trip.covers ?? []) {
      if (!COVER_PATTERN.test(cover)) {
        throw new Error(`Invalid cover "${cover}" in trip "${trip.id}".`)
      }
    }

    for (const leg of trip.straightLegs ?? []) {
      if (leg.length !== 2 || leg[0] === leg[1]) {
        throw new Error(`Invalid straight leg in trip "${trip.id}".`)
      }
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
  routes: Readonly<Record<string, TripRoute>> = {},
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
  const legs = path.slice(1).map((to, index): TripLeg => {
    const from = path[index]!
    const straight = (trip.straightLegs ?? []).some(
      ([first, second]) =>
        (first === from.id && second === to.id) || (first === to.id && second === from.id),
    )
    const route = straight ? undefined : routes[getLegKey(from, to)]

    return { from, straight, to, ...(route ? { route } : {}) }
  })
  // Road distance where a route is cached, otherwise as the crow flies.
  const distanceKm = legs.reduce(
    (total, leg) =>
      total + (leg.route ? leg.route.distance / 1000 : getDistanceKm(leg.from, leg.to)),
    0,
  )

  return {
    ...trip,
    dayCount: getDayOffset(trip.start, trip.end) + 1,
    distanceKm: Math.round(distanceKm),
    drivingHours: Math.round(
      legs.reduce((total, leg) => total + (leg.route?.duration ?? 0), 0) / 3600,
    ),
    legs,
    path,
    placeCount: new Set(path.map((location) => location.id)).size,
    stops,
    trailingWaypoints,
  }
}

/** Identifies a leg by its end coordinates, so moving a place invalidates its route. */
export function getLegKey(from: MapLocation, to: MapLocation) {
  const point = (location: MapLocation) =>
    `${location.longitude.toFixed(5)},${location.latitude.toFixed(5)}`

  return `${point(from)};${point(to)}`
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
