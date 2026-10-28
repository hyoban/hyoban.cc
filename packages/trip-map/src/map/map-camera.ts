import type { CameraOptions, Map as MapboxMap, PaddingOptions } from 'mapbox-gl'
import type { PlaceMarker, TripSummary } from '../player/types.ts'
import { TRIP_FRAME_MAX_ZOOM } from '../static-map.ts'
import { getFramingPitch, getMapPitch } from './basemap.ts'
import { bringIntoClearArea, planOnMap } from './clear-area.ts'
import type { MapSides } from './map-sides.ts'
import { CLUSTER_MAX_ZOOM, getAllPlacesView } from './place-markers.ts'

// Where the map looks while browsing, outside a trip's own camera: every place framed,
// a place or a trip's route brought clear of what's open on the map's side, and the views
// kept in the history for going back to, until the viewer moves the map themselves.

/** The history state's key for the view a selection's address was left from. */
export const VIEW_STATE_KEY = 'tripMapView'

/** Where the map was, kept in the history for going back to. */
type SavedView = {
  camera: CameraOptions
  framedOnAll: boolean
  /** This page's own, not one kept before a reload. */
  key: string
  /** `handMoves` then. */
  moves: number
  pitchedByHand: boolean
}

export type MapCamera = ReturnType<typeof createMapCamera>

export function createMapCamera({
  frameRoute,
  getMap,
  getShownPlaces,
  isLoaded,
  isNarrow,
  isPlaying,
  places,
  reducedMotion,
  sides,
}: {
  /** Flies to a trip's route in what `padding` leaves of the map. */
  frameRoute: (id: string, padding: Required<PaddingOptions>, options: { lean?: boolean }) => void
  getMap: () => MapboxMap | undefined
  /** The places on the map, those of the period picked. */
  getShownPlaces: () => readonly PlaceMarker[]
  isLoaded: () => boolean
  isNarrow: () => boolean
  /** Whether a trip's camera has the map. */
  isPlaying: () => boolean
  places: readonly PlaceMarker[]
  reducedMotion: MediaQueryList
  sides: MapSides
}) {
  // Whether the view is still the one framing every place, untouched since: only then
  // does a change of width frame them again.
  let framedOnAll = true
  // Whether the viewer has tipped the map themselves: their zooming leaves it as they set
  // it from then on, until the map frames every place again or the setting changes.
  let pitchedByHand = false
  // The moves the viewer has made themselves, counted: a view kept for going back to
  // goes back only while none has been made since.
  let handMoves = 0
  // Tells the views this map kept from those of one before it, as a reload had.
  const viewKey = Math.random().toString(36).slice(2)

  /** The map, once it has loaded. */
  function getLoadedMap() {
    return isLoaded() ? getMap() : undefined
  }

  function getAnimationDuration() {
    return reducedMotion.matches ? 0 : 500
  }

  /** Where the map is now, to go back to; nothing while a trip's camera has it. */
  function getView(): SavedView | undefined {
    const map = getLoadedMap()

    if (!map || isPlaying()) {
      return undefined
    }

    const { lat, lng } = map.getCenter()

    return {
      camera: {
        bearing: map.getBearing(),
        center: [lng, lat],
        padding: map.getPadding(),
        pitch: map.getPitch(),
        zoom: map.getZoom(),
      },
      framedOnAll,
      key: viewKey,
      moves: handMoves,
      pitchedByHand,
    }
  }

  /** Whether a view kept is still one to go back to: this page's, and not moved by hand since. */
  function isViewCurrent(view: unknown): view is SavedView {
    return (
      Boolean(view) &&
      typeof view === 'object' &&
      (view as SavedView).key === viewKey &&
      (view as SavedView).moves === handMoves
    )
  }

  /** The viewer moved the map themselves: off the framing, and off the views kept to go back to. */
  function moveByHand() {
    framedOnAll = false

    // A trip's own map is apart from the browsing going back returns to.
    if (!isPlaying()) {
      handMoves += 1
    }
  }

  /** Puts the map back where it was: every place framed again if they were, or the view itself. */
  function restoreView(view: SavedView) {
    const map = getLoadedMap()

    if (!map) {
      return
    }

    if (view.framedOnAll) {
      fitAll()
      return
    }

    framedOnAll = false
    pitchedByHand = view.pitchedByHand
    map.easeTo({ ...view.camera, duration: getAnimationDuration() })
  }

  /** The places the map frames: those of the period picked, or every one if it has none. */
  function getFramedPlaces() {
    const shown = getShownPlaces()
    return shown.length > 0 ? shown : places
  }

  /**
   * The view the map opens on: a trip's page on the trip's route, as its trip framing
   * would have it flat (a page may draw the route over the picture standing in for the
   * map, framed by getAllPlacesCamera and projectOnCamera alike), and every place shown
   * otherwise.
   */
  function getOpeningView(trip: TripSummary | undefined) {
    const line = trip ? trip.segments.flatMap((segment) => segment.line) : []

    if (line.length > 0) {
      const points = line.map(([longitude, latitude]) => ({ latitude, longitude }))
      return getAllPlacesView(points, isNarrow(), sides.getFramingInset(), TRIP_FRAME_MAX_ZOOM)
    }

    return getAllPlacesView(getFramedPlaces(), isNarrow(), sides.getFramingInset())
  }

  /** Frames every place shown; `upright` also turns the map back to north. */
  function fitAll({ upright = false } = {}) {
    const map = getMap()

    if (!map || places.length === 0) {
      return
    }

    const view = getAllPlacesView(getFramedPlaces(), isNarrow(), sides.getFramingInset())

    map.fitBounds(view.bounds, {
      ...view.fitBoundsOptions,
      ...(upright && { bearing: 0 }),
      duration: getAnimationDuration(),
      pitch: getFramingPitch(map, view.bounds, view.fitBoundsOptions),
    })
    framedOnAll = true
    pitchedByHand = false
  }

  /**
   * Has a place in what `padding` leaves of the map, close enough to stand apart from
   * its cluster: left as it is if it already is, nudged in off an edge it's by or under,
   * and otherwise to the middle of it.
   */
  function bringPlaceIntoView(place: PlaceMarker, padding: Required<PaddingOptions>) {
    const map = getLoadedMap()

    if (!map) {
      return
    }

    const center: [number, number] = [place.longitude, place.latitude]
    const zoom = CLUSTER_MAX_ZOOM + 1

    if (map.getZoom() >= zoom) {
      const plan = planOnMap(map, [center], padding)

      if (plan.kind === 'stay') {
        return
      }

      if (plan.kind === 'pan') {
        framedOnAll = false
        bringIntoClearArea(map, [center], padding, plan, getAnimationDuration())
        return
      }
    }

    framedOnAll = false
    map.easeTo({
      center,
      duration: getAnimationDuration(),
      padding,
      zoom: Math.max(map.getZoom(), zoom),
    })
  }

  /**
   * Moves the map out from under a list as it opens, or as the window's resized beside
   * it: every place framed beside it, if they were in view, or else the places on screen
   * brought out from under it, as little as it takes. If none is under it, the map stays.
   */
  function moveMapFromUnder(list: HTMLElement) {
    const map = getLoadedMap()

    if (!map) {
      return
    }

    if (framedOnAll) {
      fitAll()
      return
    }

    const container = map.getContainer()
    const onScreen = getShownPlaces()
      .map((place): [number, number] => [place.longitude, place.latitude])
      .filter((coordinates) => {
        const { x, y } = map.project(coordinates)
        return x >= 0 && x <= container.clientWidth && y >= 0 && y <= container.clientHeight
      })

    if (onScreen.length === 0) {
      return
    }

    const padding = sides.getSidePadding(list)

    bringIntoClearArea(
      map,
      onScreen,
      padding,
      planOnMap(map, onScreen, padding),
      getAnimationDuration(),
    )
  }

  /**
   * Flies to a trip's route beside what's on the map's side: the trip list, or its sidebar.
   * `lean` flies there even if it's in view, to lean back over a route opened on flat.
   */
  function frameTripBeside(sideElement: HTMLElement, id: string, options: { lean?: boolean } = {}) {
    if (!getLoadedMap()) {
      return
    }

    framedOnAll = false

    // Wider than for a place, for the route's name above it and the legend below.
    const side = sides.getSidePadding(sideElement)

    frameRoute(
      id,
      {
        bottom: side.bottom + 64,
        left: side.left + 48,
        right: side.right + 48,
        top: side.top + 32,
      },
      options,
    )
  }

  /**
   * Opening a place leaves the map padded for the drawer, which would keep later moves
   * (a cluster opening, say) off centre once it's gone. Drops the padding without
   * moving what's on screen.
   */
  function clearDrawerPadding() {
    const map = getLoadedMap()

    // A trip's camera sets its own padding every frame.
    if (!map || isPlaying()) {
      return
    }

    const container = map.getContainer()
    const center = map.unproject([container.clientWidth / 2, container.clientHeight / 2])

    map.jumpTo({ center, padding: { bottom: 0, left: 0, right: 0, top: 0 } })
  }

  /** Leans the map as far as it does at its zoom, unless the viewer tipped it or a trip plays. */
  function leanToZoom() {
    const map = getMap()

    if (!map || pitchedByHand || isPlaying()) {
      return
    }

    const pitch = getMapPitch(map, map.getZoom())

    if (Math.abs(map.getPitch() - pitch) < 0.1) {
      return
    }

    map.easeTo({ duration: getAnimationDuration(), pitch })
  }

  return {
    bringPlaceIntoView,
    clearDrawerPadding,
    fitAll,
    frameTripBeside,
    get framedOnAll() {
      return framedOnAll
    },
    getAnimationDuration,
    getFramedPlaces,
    getOpeningView,
    getView,
    isViewCurrent,
    leanToZoom,
    moveByHand,
    moveMapFromUnder,
    get pitchedByHand() {
      return pitchedByHand
    },
    set pitchedByHand(value: boolean) {
      pitchedByHand = value
    },
    restoreView,
  }
}
