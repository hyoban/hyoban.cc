import type { getTripData } from '../moments/trip-data'
import type { TripSlide } from './slides'

// The trip data the player reads is exactly what the build inlines or serves at
// /trip/<id>/data.json, so its types come from there and can't drift apart.

export type TripData = ReturnType<typeof getTripData>
export type TripStop = TripData['stops'][number]
export type TripImage = TripData['covers'][number]
export type TripPlace = TripStop['places'][number]
export type TripWaypoint = TripStop['waypoints'][number]
export type Slide = TripSlide<TripStop>

/** The picker's view of a trip; its playback data loads when it starts. */
export type TripSummary = { id: string; pageTitle: string }

/** One place the route runs through, a stop's own place or a waypoint before it. */
export type TripPoint = {
  coordinates: [number, number]
  /** The trip day a waypoint was passed on. */
  day?: number
  /** `第 N 天 · date · time`, shown above a waypoint's name on the map. */
  eyebrow?: string
  name: string
  stopIndex: number
  time?: string
  waypoint: boolean
}

export type MapLabel = {
  coordinates: [number, number]
  eyebrow?: string
  name: string
}
