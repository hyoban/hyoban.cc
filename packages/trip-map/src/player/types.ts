import type { WeatherIcon } from '../icons.ts'
import type { TravelMode } from '../trips.ts'
import type { TripSlide } from './slides.ts'

// What the player plays: one trip's data, as the site builds it and inlines in the page
// or serves as JSON (see the README), and every trip's summary for the overview map.

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

export type TripPlace = { latitude: number; longitude: number; name: string }

/** A place passed on the way to a stop, and when the route passed it. */
export type TripDataWaypoint = TripPlace & {
  /** The date as shown on the cards, like `10月1日周四`. */
  date: string
  /** 1-based day of the trip. */
  day: number
  /** `HH:mm`, when known. */
  time?: string | undefined
}

/** A stop on the trip: a Moment's places, photos and words. */
export type TripDataStop = {
  /** The URL hash that opens the trip on this card. */
  anchor: string
  date: string
  day: number
  /** Where the Moment itself can be read. */
  href: string
  images: TripImage[]
  /** The places' names, joined. */
  name: string
  places: TripPlace[]
  text: string
  /** `HH:mm`, when the Moment has a time. */
  time?: string | undefined
  /** An embeddable video player's URL, played on the card instead of photos. */
  video?: string
  /** Waypoints passed on the way from the previous stop to this one. */
  waypoints: TripDataWaypoint[]
}

/** Each trip day's weather by day number; days without data are left out. */
export type TripWeather = Record<number, { icon: WeatherIcon; label: string }>

export type TripData = {
  /** Photos for the intro and summary cards, best first. */
  covers: TripImage[]
  dateRange: string
  dayCount: number
  drivingHours: number
  drivingKm: number
  id: string
  /** Road or rail geometry from each place of the route to the next; null draws it straight. */
  legs: (readonly (readonly [number, number])[] | null)[]
  legModes: TravelMode[]
  /** The background music's URL, when the trip has some. */
  music?: string | undefined
  /** The document title while the trip plays. */
  pageTitle: string
  photoCount: number
  placeCount: number
  stops: TripDataStop[]
  title: string
  /** Waypoints after the last stop. */
  trailingWaypoints: TripDataWaypoint[]
  weather: TripWeather
}

export type Slide = TripSlide<TripDataStop>

/** The overview's view of a trip: its route to draw and name. Its playback data loads when it starts. */
export type TripSummary = {
  dateRange: string
  id: string
  pageTitle: string
  /** Stretches of the route: drives, and the ferries or flights drawn dashed. */
  segments: { line: [number, number][]; straight: boolean }[]
  title: string
}

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
