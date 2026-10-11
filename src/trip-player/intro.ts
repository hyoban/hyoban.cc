import { pickCovers } from './slides'
import type { TripData } from './types'

// The side card's intro, which the trip pages also render on the server so it shows
// (and its photos load) before the map script has run. Kept free of the DOM for that.

const INTRO_COVER_COUNT = 9

// Narrow screens keep one row so the map stays visible above the card.
export const MOSAIC_CLASS =
  'mt-3 grid shrink-0 grid-cols-3 gap-1 @max-[48rem]:[&>:nth-child(n+4)]:hidden'
export const MOSAIC_IMAGE_CLASS =
  'block aspect-square w-full rounded-[0.3125rem] bg-inline object-cover'

/** Photos for the intro and summary. */
export function getIntroCovers(trip: TripData) {
  return pickCovers(trip.covers, trip.stops, INTRO_COVER_COUNT)
}

/** `date range · N 天 · N 条记录`, under the trip's title on both intro cards. */
export function getTripMeta(trip: TripData) {
  return `${trip.dateRange} · ${trip.dayCount} 天 · ${trip.stops.length} 条记录`
}
