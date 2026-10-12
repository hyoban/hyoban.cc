import { getDistanceKm as getCoordinateDistanceKm } from './geo.ts'
import type { MapLocation } from './locations.ts'

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
  /**
   * Places driven back the way they came, as `[from, to]` location ids: the drive back
   * from `to` follows the road route from `from`, reversed, rather than its own.
   */
  sameWayBack?: readonly (readonly [string, string])[]
  start: string
  /**
   * Legs between two location ids taken by ferry, plane or train; drawn dashed instead
   * of along roads. A train leg follows its rail line once one is cached, otherwise straight.
   */
  straightLegs?: readonly (readonly [string, string, Exclude<TravelMode, 'car'>])[]
  title: string
  waypoints?: readonly TripWaypointDefinition[]
}>

type TripLocation = MapLocation & { id: string }

/**
 * A driving route between two places, or a rail line for a train leg, cached in
 * src/data/trip-routes.json.
 */
export type TripRoute = Readonly<{
  coordinates: readonly (readonly [number, number])[]
  /** Metres along the route. */
  distance: number
  /** Seconds of driving without traffic, as Mapbox estimates it. */
  duration?: number
}>

export type TravelMode = 'car' | 'ferry' | 'plane' | 'train'

export type TripLeg = Readonly<{
  by: TravelMode
  from: TripLocation
  /** Driven back along the route the other way (see `sameWayBack`), so not fetched. */
  retraced?: true
  route?: TripRoute
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
    /** Kilometres driven; ferries and flights aren't counted. */
    drivingKm: number
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
      if (leg[0] === leg[1] || !['ferry', 'plane', 'train'].includes(leg[2])) {
        throw new Error(`Invalid straight leg in trip "${trip.id}".`)
      }
    }

    for (const [from, to] of trip.sameWayBack ?? []) {
      if (from === to) {
        throw new Error(`Invalid same-way-back leg in trip "${trip.id}".`)
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
    const by =
      (trip.straightLegs ?? []).find(
        ([first, second]) =>
          (first === from.id && second === to.id) || (first === to.id && second === from.id),
      )?.[2] ?? 'car'
    const retraced =
      by === 'car' &&
      (trip.sameWayBack ?? []).some(([first, second]) => first === to.id && second === from.id)
    const outbound = retraced ? routes[getLegKey(to, from)] : undefined
    const route =
      by === 'train'
        ? routes[getTrainLegKey(from, to)]
        : by !== 'car'
          ? undefined
          : outbound
            ? { ...outbound, coordinates: [...outbound.coordinates].reverse() }
            : retraced
              ? undefined
              : routes[getLegKey(from, to)]

    return {
      by,
      from,
      to,
      ...(retraced ? { retraced: true as const } : {}),
      ...(route ? { route } : {}),
    }
  })
  // Only what was driven: road distance where a route is cached, otherwise as the
  // crow flies. Ferries, flights and trains aren't driving, so they'd only inflate it.
  const drivingKm = legs.reduce(
    (total, leg) =>
      leg.by !== 'car'
        ? total
        : total + (leg.route ? leg.route.distance / 1000 : getDistanceKm(leg.from, leg.to)),
    0,
  )

  return {
    ...trip,
    dayCount: getDayOffset(trip.start, trip.end) + 1,
    drivingKm: Math.round(drivingKm),
    drivingHours: Math.round(
      legs.reduce((total, leg) => total + (leg.by === 'car' ? (leg.route?.duration ?? 0) : 0), 0) /
        3600,
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

/** Identifies a train leg's rail line, kept apart from a drive between the same places. */
export function getTrainLegKey(from: MapLocation, to: MapLocation) {
  return `train:${getLegKey(from, to)}`
}

/** Identifies a day's weather at a place, to about 1 km. */
export function getWeatherKey(dateKey: string, location: MapLocation) {
  return `${dateKey};${location.longitude.toFixed(2)},${location.latitude.toFixed(2)}`
}

/** The place a trip day's weather is taken at: the first place of its first stop. */
export function getDayPlaces<T extends TripMoment>(trip: Trip<T>) {
  const places = new Map<string, TripLocation>()

  for (const stop of trip.stops) {
    if (!places.has(stop.dateKey)) {
      places.set(stop.dateKey, stop.locations[0]!)
    }
  }

  return places
}

/** Great-circle distance between two places. */
export function getDistanceKm(from: MapLocation, to: MapLocation) {
  return getCoordinateDistanceKm([from.longitude, from.latitude], [to.longitude, to.latitude])
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
