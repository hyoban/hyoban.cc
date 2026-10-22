import type { Period } from './period.ts'

// What the map page's address means: the place a hash names, the trip a path opens,
// the path that opens the list of trips, the one that narrows the map to a year or a
// month, and the URLs of what the client fetches. A site with other paths passes its own.

const TRIP_PATH_PATTERN = /^\/trip\/([^/]+)\/?$/
const PERIOD_PATH_PATTERN = /^\/map\/(\d{4})\/(?:(\d{2})\/?)?$/

/** The key in `history.state` that marks an entry the map pushed for a selection. */
export const HISTORY_STATE_KEY = 'tripMapSelection'

/** The id a `#hash` names, decoded; nothing for an empty or malformed one. */
export function parseHash(hash: string) {
  if (!hash) {
    return undefined
  }

  try {
    return decodeURIComponent(hash.slice(1))
  } catch {
    return undefined
  }
}

export const defaultUrls = {
  /** A place's drawer content, an HTML fragment. */
  getPlaceUrl: (id: string) => `/map/place/${encodeURIComponent(id)}/`,
  /** A trip's playback data, `TripData` as JSON. */
  getTripDataUrl: (id: string) => `/trip/${encodeURIComponent(id)}/data.json`,
  /** The trip a pathname opens, if any. */
  getTripId: (pathname: string) => {
    const match = pathname.match(TRIP_PATH_PATTERN)

    try {
      return match ? decodeURIComponent(match[1]!) : undefined
    } catch {
      return undefined
    }
  },
  /** A year's Moments for the list beside the map, an HTML fragment. */
  getMomentsUrl: (year: number) => `/map/${year}/moments/`,
  /** The year, or the month of one, a pathname narrows the map to, if any. */
  getPeriod: (pathname: string): Period | undefined => {
    const match = pathname.match(PERIOD_PATH_PATTERN)
    const month = match?.[2] === undefined ? undefined : Number(match[2])

    if (!match || (month !== undefined && (month < 1 || month > 12))) {
      return undefined
    }

    return { month, year: Number(match[1]) }
  },
  /** The map narrowed to a year, or a month of one, with its Moments beside it. */
  getPeriodPath: (period: Period) =>
    period.month === undefined
      ? `/map/${period.year}/`
      : `/map/${period.year}/${String(period.month).padStart(2, '0')}/`,
  /** A trip's page. */
  getTripPath: (id: string) => `/trip/${encodeURIComponent(id)}/`,
  /** The map's own page, which closing a trip goes back to. */
  mapPath: '/map/',
  /** The map with the list of trips open beside it. */
  tripListPath: '/trip/',
}

export type Urls = typeof defaultUrls

export function getHistoryState() {
  return history.state && typeof history.state === 'object'
    ? (history.state as Record<string, unknown>)
    : {}
}
