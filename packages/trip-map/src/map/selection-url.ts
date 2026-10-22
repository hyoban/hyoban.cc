import type { Period } from './period.ts'

// What the map page's address means, all in its path: the map, narrowed to a year or a
// month of one or not, with a place's drawer open over it or not; the list of trips,
// narrowed the same way or not; a trip's sidebar; a trip playing, from a card of it or its start; and the URLs
// of what the client fetches. A site with other paths passes its own.

const MAP_PATH_PATTERN = /^\/map\/(?:(\d{4})\/(?:(\d{2})\/)?)?(?:place\/([^/]+)\/)?$/
// A trip's id, `2026-yunnan`, or its year and the rest, `2026/yunnan`: both lead to it.
const TRIP_ID = String.raw`(?:(\d{4})\/)?([^/]+)`
const TRIP_LIST_PATH_PATTERN = /^\/trip\/(?:(\d{4})\/(?:(\d{2})\/)?)?$/
const PICKED_TRIP_PATH_PATTERN = new RegExp(String.raw`^\/trip\/${TRIP_ID}\/$`)
const PLAYING_TRIP_PATH_PATTERN = new RegExp(String.raw`^\/trip\/${TRIP_ID}\/play\/(?:([^/]+)\/)?$`)

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

/** The trip a pathname's match names, from its id or its year and the rest. */
function getMatchedTripId(match: RegExpMatchArray | null | undefined) {
  const rest = decode(match?.[2])
  return rest && (match?.[1] ? `${match[1]}-${rest}` : rest)
}

/** A trip's part of a path: its id, or with `nested` its year and the rest if it has one. */
function getTripSegment(id: string, nested?: boolean) {
  const [, year, rest] = id.match(/^(\d{4})-(.+)$/) ?? []
  return nested && year && rest ? `${year}/${encodeURIComponent(rest)}` : encodeURIComponent(id)
}

function decode(segment: string | undefined) {
  try {
    return segment === undefined ? undefined : decodeURIComponent(segment)
  } catch {
    return undefined
  }
}

/** A map address with a trailing slash, as the site's pages have. */
function withSlash(pathname: string) {
  return pathname.endsWith('/') ? pathname : `${pathname}/`
}

/** A year, and a month of it if given, from a path's digits; none for a month out of range. */
function toPeriod(year: string | undefined, month: string | undefined): Period | undefined {
  const monthNumber = month === undefined ? undefined : Number(month)

  if (year === undefined || (monthNumber !== undefined && (monthNumber < 1 || monthNumber > 12))) {
    return undefined
  }

  return { month: monthNumber, year: Number(year) }
}

/** The part of a path a period takes: `2025/`, `2025/06/`, or nothing. */
function getPeriodSegment(period: Period | undefined) {
  const month = period?.month === undefined ? '' : `${String(period.month).padStart(2, '0')}/`
  return period ? `${period.year}/${month}` : ''
}

/** What a map address shows: the period it's narrowed to, and the place open; none off the map. */
function parseMapPath(pathname: string) {
  const match = withSlash(pathname).match(MAP_PATH_PATTERN)
  const period = match ? toPeriod(match[1], match[2]) : undefined

  if (!match || (match[1] !== undefined && !period)) {
    return undefined
  }

  return { period, place: decode(match[3]) }
}

export const defaultUrls = {
  /** A place's drawer content, an HTML fragment. */
  getPlaceUrl: (id: string) => `/map/place/${encodeURIComponent(id)}/moments/`,
  /** A trip's playback data, `TripData` as JSON. */
  getTripDataUrl: (id: string) => `/trip/${encodeURIComponent(id)}/data.json`,
  /** A trip's sidebar content, an HTML fragment. */
  getTripMomentsUrl: (id: string) => `/trip/${encodeURIComponent(id)}/moments/`,
  /** A year's Moments for the list beside the map, an HTML fragment. */
  getMomentsUrl: (year: number) => `/map/${year}/moments/`,
  /** Whether a pathname is the map's own: the map, a period of it, a place on it. */
  isMapPath: (pathname: string) => parseMapPath(pathname) !== undefined,
  /** The year, or the month of one, a pathname narrows the map to, if any. */
  getPeriod: (pathname: string): Period | undefined => parseMapPath(pathname)?.period,
  /** The place whose drawer a pathname opens, if any. */
  getPlaceId: (pathname: string) => parseMapPath(pathname)?.place,
  /** The map, narrowed to `period` or not, with `place` open or not. */
  getMapPath: (period?: Period, place?: string) =>
    `/map/${getPeriodSegment(period)}${place ? `place/${encodeURIComponent(place)}/` : ''}`,
  /** Whether a pathname is the trip list's: every trip, or a year's or a month's. */
  isTripListPath: (pathname: string) => TRIP_LIST_PATH_PATTERN.test(withSlash(pathname)),
  /** The year, or the month of one, the trip list's pathname narrows it and the map to. */
  getTripListPeriod: (pathname: string) => {
    const match = withSlash(pathname).match(TRIP_LIST_PATH_PATTERN)
    return match ? toPeriod(match[1], match[2]) : undefined
  },
  /** The trip list, narrowed to `period` or not: `/trip/`, `/trip/2025/`, `/trip/2025/06/`. */
  getTripListPath: (period?: Period) => `/trip/${getPeriodSegment(period)}`,
  /** The trip a pathname plays, if any. */
  getTripId: (pathname: string) =>
    getMatchedTripId(withSlash(pathname).match(PLAYING_TRIP_PATH_PATTERN)),
  /** The card of the trip playing a pathname starts on, a stop's anchor, if any. */
  getTripStop: (pathname: string) =>
    decode(withSlash(pathname).match(PLAYING_TRIP_PATH_PATTERN)?.[3]),
  /**
   * A trip playing, from the card `stop` names or its start; `nested`, under its year
   * (`/trip/2026/yunnan/play/`) rather than its id.
   */
  getTripPath: (id: string, stop?: string, nested?: boolean) =>
    `/trip/${getTripSegment(id, nested)}/play/${stop ? `${encodeURIComponent(stop)}/` : ''}`,
  /** The trip whose sidebar a pathname opens, if any. */
  getPickedTripId: (pathname: string) =>
    getMatchedTripId(withSlash(pathname).match(PICKED_TRIP_PATH_PATTERN)),
  /** A trip's sidebar beside the map, its route lifted, not playing yet; `nested`, as above. */
  getPickedTripPath: (id: string, nested?: boolean) => `/trip/${getTripSegment(id, nested)}/`,
  /** Whether a pathname leads to a trip by its year and the rest, `/trip/2026/yunnan/`. */
  isNestedTripPath: (pathname: string) =>
    /^\/trip\/\d{4}\/[^/]+\//.test(withSlash(pathname)) &&
    !TRIP_LIST_PATH_PATTERN.test(withSlash(pathname)),
  /** The map's own page, which closing a trip goes back to. */
  mapPath: '/map/',
  /** The map with the list of every trip open beside it. */
  tripListPath: '/trip/',
}

export type Urls = typeof defaultUrls

export function getHistoryState() {
  return history.state && typeof history.state === 'object'
    ? (history.state as Record<string, unknown>)
    : {}
}
