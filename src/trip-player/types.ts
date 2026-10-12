import type { getTripData, getTripSummary } from '../moments/trip-data'
import type { TripSlide } from './slides'

// The trip data the player reads is exactly what the build inlines or serves at
// /trip/<id>/data.json, so its types come from there and can't drift apart.

export type TripData = ReturnType<typeof getTripData>
export type TripStop = TripData['stops'][number]
export type TripImage = TripData['covers'][number]
export type TripPlace = TripStop['places'][number]
export type TripWaypoint = TripStop['waypoints'][number]
export type Slide = TripSlide<TripStop>

/** The overview's view of a trip; its playback data loads when it starts. */
export type TripSummary = ReturnType<typeof getTripSummary>

/** One place the route runs through, a stop's own place or a waypoint before it. */
export type TripPoint = {
  coordinates: [number, number]
  /** When the route passes it: a stop's own day and time, or the waypoint's. */
  date: string
  day: number
  name: string
  stopIndex: number
  time?: string | undefined
  waypoint: boolean
}

export type MapLabel = {
  coordinates: [number, number]
  name: string
}
