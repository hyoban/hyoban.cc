// What the map page's address means: the place a hash names, the trip a path opens,
// the path that opens the list of trips, the path that picks a year, and the URLs of what the client fetches. A site with other paths passes its own.

const TRIP_PATH_PATTERN = /^\/trip\/([^/]+)\/?$/
const YEAR_PATH_PATTERN = /^\/map\/(\d{4})\/?$/

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
  /** The year a pathname picks in the legend, if any. */
  getYear: (pathname: string) => {
    const match = pathname.match(YEAR_PATH_PATTERN)
    return match ? Number(match[1]) : undefined
  },
  /** The map with only a year's places and trips on it. */
  getYearPath: (year: number) => `/map/${year}/`,
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
