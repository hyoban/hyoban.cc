import { formatDateRange } from '../format.ts'
import { t, type Messages } from '../messages.ts'
import { pickCovers } from './slides.ts'
import type { TripData } from './types.ts'

// The side card's intro, which the trip pages also render on the server so it shows
// (and its photos load) before the map script has run. Kept free of the DOM for that.

const INTRO_COVER_COUNT = 9

// Styled in styles.css; narrow screens keep one row so the map stays visible above the card.
export const MOSAIC_CLASS = 'trip-map-mosaic'
export const MOSAIC_IMAGE_CLASS = 'trip-map-mosaic__image'

/** Photos for the intro and summary. */
export function getIntroCovers(trip: TripData) {
  return pickCovers(trip.covers, trip.stops, INTRO_COVER_COUNT)
}

/** `date range\u2003N 天\u2003N 条记录`, under the trip's title on both intro cards. */
export function getTripMeta(trip: TripData, messages: Messages) {
  return t(messages, 'tripMeta', {
    days: trip.dayCount,
    moments: trip.stops.length,
    range: formatDateRange(trip.start, trip.end, messages.locale),
  })
}
