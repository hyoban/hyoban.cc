import type { Coordinates } from '../geo.ts'
import type { WeatherIcon } from '../icons.ts'
import type { TravelMode } from '../trips.ts'
import type { TripSlide } from './slides.ts'

// What the player plays: one trip's data, as `shapeTripData` builds it from a built
// trip and the site inlines in the page or serves as JSON, and every trip's summary
// (`getTripSummary`) for the overview map.

/** A still image sized for the trip cards, with its responsive sources. */
export type TripImage = {
  alt: string
  height: number
  src: string
  srcset: string
  thumbnail: string
  /** A live photo's motion, played once over the still. */
  video?: string
  width: number
}

/**
 * An image as a site gives it to `shapeTripData`: at least its `src` and size. Without
 * `srcset` the one source serves every width, and without `thumbnail` the thumbnails
 * load the image itself.
 */
export type TripImageInput = {
  alt?: string | undefined
  height: number
  src: string
  srcset?: string | undefined
  thumbnail?: string | undefined
  video?: string | undefined
  width: number
}

/** A place with posts, as the map page lists them (see `getPlaceMarkers`). */
export type PlaceMarker = {
  color: string
  /** Posts there. */
  count: number
  id: string
  firstYear: number
  latitude: number
  longitude: number
  name: string
  /** What and where it is, like `景点 · 合肥市 肥西县`, shown under its name in the drawer. */
  detail?: string | undefined
  /** The months it has posts in (`2025-06`), newest first. */
  months: string[]
  /** The years it has posts in, newest first. */
  years: number[]
}

/** The totals a screenshot of the map puts in its header (see `getMapStats`). */
export type MapStats = {
  drivingKm: number
  firstYear: number
  lastYear: number
  moments: number
  places: number
  trips: number
}

export type TripPlace = { latitude: number; longitude: number; name: string }

/** A place passed on the way to a stop, and when the route passed it. */
export type TripDataWaypoint = TripPlace & {
  /** The day's date as `YYYY-MM-DD`. */
  dateKey: string
  /** 1-based day of the trip. */
  day: number
  /** `HH:mm`, when known. */
  time?: string | undefined
}

/** A stop on the trip: a Moment's places, photos and words. */
export type TripDataStop = {
  /** The URL hash that opens the trip on this card. */
  anchor: string
  dateKey: string
  day: number
  /** Where the Moment itself can be read. */
  href: string
  images: TripImage[]
  places: TripPlace[]
  text: string
  /** `HH:mm`, when the Moment has a time. */
  time?: string | undefined
  /** An embeddable video player's URL, played on the card instead of photos. */
  video?: string
  /** Waypoints passed on the way from the previous stop to this one. */
  waypoints: TripDataWaypoint[]
}

/** From one place of the route to the next: along a road or rail line, or straight. */
export type TripDataLeg = {
  /** The road or rail geometry between the two places; null draws a straight line. */
  line: readonly Coordinates[] | null
  mode: TravelMode
}

/** Each trip day's weather by day number; days without data are left out. */
export type TripWeather = Record<number, { icon: WeatherIcon; label: string }>

/** What each trip day was driven by day number, as its label; days without data are left out. */
export type TripDriving = Record<number, string>

export type TripData = {
  /** Photos for the intro and summary cards, best first. */
  covers: TripImage[]
  dayCount: number
  /** What the car's odometer says of each day, when the site has it. */
  driving?: TripDriving | undefined
  drivingHours: number
  drivingKm: number
  /** The last day, as `YYYY-MM-DD`. */
  end: string
  id: string
  /** One per pair of consecutive places in the route (every stop's waypoints then its places). */
  legs: TripDataLeg[]
  /** The background music's URL, when the trip has some. */
  music?: string | undefined
  photoCount: number
  placeCount: number
  /** The first day, as `YYYY-MM-DD`. */
  start: string
  stops: TripDataStop[]
  title: string
  /** Waypoints after the last stop. */
  trailingWaypoints: TripDataWaypoint[]
  weather: TripWeather
}

export type Slide = TripSlide<TripDataStop>

/** The overview's view of a trip: its route to draw and name. Its playback data loads when it starts. */
export type TripSummary = {
  end: string
  id: string
  /** Stretches of the route: drives, and the ferries or flights drawn dashed. */
  segments: { line: [number, number][]; straight: boolean }[]
  start: string
  title: string
}

/** One place the route runs through, a stop's own place or a waypoint before it. */
export type TripPoint = {
  coordinates: [number, number]
  /** When the route passes it: a stop's own day and time, or the waypoint's. */
  dateKey: string
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
