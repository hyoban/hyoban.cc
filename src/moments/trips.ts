import type { MapLocation } from './map-locations.ts'

export type TripDefinition = Readonly<{
  end: string
  id: string
  start: string
  title: string
}>

type TripMoment = Readonly<{
  dateKey: string
  location?: MapLocation & { id: string }
}>

export type Trip<T extends TripMoment> = TripDefinition &
  Readonly<{
    dayCount: number
    distanceKm: number
    placeCount: number
    stops: (T & { location: MapLocation & { id: string } })[]
  }>

const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
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
  }

  return trips
}

/** Collects the located moments inside a trip's date range. `moments` must be sorted oldest first. */
export function buildTrip<T extends TripMoment>(trip: TripDefinition, moments: T[]): Trip<T> {
  const stops = moments.filter(
    (moment): moment is T & { location: MapLocation & { id: string } } =>
      moment.location !== undefined && moment.dateKey >= trip.start && moment.dateKey <= trip.end,
  )
  let distanceKm = 0

  for (let index = 1; index < stops.length; index += 1) {
    distanceKm += getDistanceKm(stops[index - 1]!.location, stops[index]!.location)
  }

  return {
    ...trip,
    dayCount: getDayOffset(trip.start, trip.end) + 1,
    distanceKm: Math.round(distanceKm),
    placeCount: new Set(stops.map((stop) => stop.location.id)).size,
    stops,
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

function isDateKey(value: string) {
  return DATE_KEY_PATTERN.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
}
