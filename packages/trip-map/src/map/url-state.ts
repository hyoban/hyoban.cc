import type { PlaceMarker } from '../player/types.ts'
import { getHistoryState, parseHash, type Urls } from './selection-url.ts'

// What the address picks, read against what the map has: a place it knows, a trip it can
// list or play, the trip list if the page has one, and a period it has places in. An
// address naming anything else picks nothing.

export type UrlState = ReturnType<typeof createUrlState>

export function createUrlState({
  canPlay,
  hasPlace,
  hasTrip,
  hasTripList,
  places,
  urls,
}: {
  /** Whether the player has the trip. */
  canPlay: (id: string) => boolean
  hasPlace: (id: string) => boolean
  /** Whether the trip is among the page's trips. */
  hasTrip: (id: string) => boolean
  hasTripList: boolean
  places: readonly PlaceMarker[]
  urls: Urls
}) {
  /** The place whose drawer the address opens, on the map. */
  function getPlaceId() {
    const id = urls.getPlaceId(window.location.pathname)
    return id && hasPlace(id) ? id : undefined
  }

  /** The trip the trip list's address picks, `/trip/<id>/`. */
  function getPickedTripId() {
    const id = urls.getPickedTripId(window.location.pathname)
    return id && hasTrip(id) ? id : undefined
  }

  /** The trip the address plays. */
  function getTripId() {
    const id = urls.getTripId(window.location.pathname)
    return id && canPlay(id) ? id : undefined
  }

  /** Whether the address leads to its trip by year and the rest; trips opened from it do too. */
  function isNested() {
    return urls.isNestedTripPath(window.location.pathname)
  }

  function isTripList() {
    return hasTripList && urls.isTripListPath(window.location.pathname)
  }

  function hasPlacesIn(year: number) {
    return places.some((place) => place.years.includes(year))
  }

  /** Whether the address is the map's own, narrowed to a period it has places in or not. */
  function isMap() {
    const period = urls.getPeriod(window.location.pathname)
    return urls.isMapPath(window.location.pathname) && (!period || hasPlacesIn(period.year))
  }

  /** The period the map's address, or the trip list's, narrows the map to. */
  function getPeriod() {
    const { pathname } = window.location
    const period = urls.getPeriod(pathname) ?? (hasTripList && urls.getTripListPeriod(pathname))

    return period && hasPlacesIn(period.year) ? period : undefined
  }

  /**
   * Older addresses kept a selection in the hash: a place on the map (`/map/#<id>`), or
   * the card a trip played from (`/trip/<id>/#<stop>`). Puts it in the path instead.
   */
  function upgradeHash() {
    const id = parseHash(window.location.hash)
    const { pathname, search } = window.location
    const pickedTripId = urls.getPickedTripId(pathname)
    let path: string | undefined

    if (!id) {
      return
    }

    if (urls.isMapPath(pathname) && hasPlace(id)) {
      path = urls.getMapPath(urls.getPeriod(pathname), id)
    } else if (pickedTripId && hasTrip(pickedTripId)) {
      path = urls.getTripPath(pickedTripId, id, urls.isNestedTripPath(pathname))
    }

    if (path) {
      history.replaceState(getHistoryState(), '', `${path}${search}`)
    }
  }

  return {
    getPeriod,
    getPickedTripId,
    getPlaceId,
    getTripId,
    isMap,
    isNested,
    isTripList,
    upgradeHash,
  }
}

/** Whether a pathname is one of the map's: the map's own, the trip list's, or a trip's. */
export function isOwnPath(urls: Urls, pathname: string) {
  return (
    urls.isMapPath(pathname) ||
    urls.isTripListPath(pathname) ||
    Boolean(urls.getTripId(pathname)) ||
    Boolean(urls.getPickedTripId(pathname))
  )
}
