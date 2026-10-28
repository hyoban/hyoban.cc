import mapboxgl from 'mapbox-gl'
import { collectMapElements } from '../elements.ts'
import { drawNextFrame, mapFrames } from '../frames.ts'
import type { Brand, MusicTrack } from '../markup.ts'
import { resolveMessages, t, type Messages } from '../messages.ts'
import { parseJson } from '../player/dom.ts'
import { getReducedMotionQuery } from '../player/motion.ts'
import { createTripOverview } from '../player/overview.ts'
import { createTripPlayer } from '../player/player.ts'
import type { MapStats, PlaceMarker, TripData, TripSummary } from '../player/types.ts'
import { readTheme } from '../dom-theme.ts'
import { getMapPitch, syncBasemapRoads, setMapPitch } from './basemap.ts'
import { createFogLayer } from './fog-layer.ts'
import { createMapCamera, VIEW_STATE_KEY, type MapCamera } from './map-camera.ts'
import { createMapSettings, type MapSettings } from './map-settings.ts'
import { createMapSides } from './map-sides.ts'
import { createMapStyle, getMapLanguage } from './map-style.ts'
import { createPlaceDrawer, type LightboxMedia } from './place-drawer.ts'
import { isPlainClick } from './link-click.ts'
import { createMomentList } from './moment-list.ts'
import { isSamePeriod, type Period } from './period.ts'
import { createPlaceMarkers } from './place-markers.ts'
import { createQuickMoves } from './quick-moves.ts'
import { captureMap, savePicture } from './screenshot.ts'
import { createTripDetails } from './trip-details.ts'
import { createTripScroll } from './trip-scroll.ts'
import { createTripList, showPeriodRows } from './trip-list.ts'
import { createUrlState, isOwnPath } from './url-state.ts'
import { defaultUrls, getHistoryState, HISTORY_STATE_KEY, type Urls } from './selection-url.ts'

// The map page: every place with posts as a dot, each opening a drawer with its
// posts; and every trip's route, each opening its playback. The page's own markup
// is the site's (see README); this finds its parts by their data attributes, and
// renders the player's parts into the containers the page gives it.

/** Something the map couldn't do; the page is told, and carries on. */
export type TripMapError = {
  cause?: unknown
  /** The place or trip concerned. */
  id?: string
  kind: 'map' | 'place' | 'trip'
  message: string
}

/** A map on a page, until `destroy`. */
export type TripMap = {
  /** The trip playing, or loading to play. */
  readonly activeTripId: string | undefined
  /** Takes the map down: before the page it's on is replaced. */
  destroy: () => void
  readonly page: HTMLElement
}

export type TripMapOptions = {
  /** Who made a video, on the presentation stage's corner. */
  brand?: Brand | undefined
  /**
   * A glTF model of a car (standing on y = 0, its nose to +z, Meshopt compression allowed)
   * that drives the route while playback flies over, in place of the car's icon. Needs
   * three.js installed; it's only loaded when the car first drives.
   */
  carModel?: string | undefined
  /**
   * Makes the lightbox's media controller for the `[data-trip-map-lightbox]` dialog;
   * without one, the drawer's images don't open in it.
   */
  createLightboxMedia?:
    | ((dialog: HTMLDialogElement, signal: AbortSignal) => LightboxMedia)
    | undefined
  /** The document title while no trip plays; the title at start otherwise. */
  documentTitle?: string | undefined
  /** The document title while the map is narrowed to a period; without it, `documentTitle`. */
  getPeriodTitle?: ((period: Period) => string) | undefined
  /**
   * The document title while a trip plays (`playing`) or is picked in the trip list;
   * without it the title is left alone.
   */
  getDocumentTitle?: ((trip: { id: string; playing: boolean; title: string }) => string) | undefined
  /**
   * The language the basemap's names are shown in (`en`, `ja`, `zh-Hans`…), by Mapbox
   * Standard itself or by @mapbox/mapbox-gl-language in another style; `false` leaves the
   * basemap as it is. Default: from the messages' locale.
   */
  mapLanguage?: string | false | undefined
  /**
   * What the addresses mean and where things are fetched from, any of `defaultUrls`:
   * `/map/[<year>/[<month>/]][place/<id>/]`, `/trip/[<id>/]`, `/trip/<id>/play/[<stop>/]`,
   * and the fragments and data under them.
   */
  urls?: Partial<Urls> | undefined
  /**
   * The document title while the trip list is open, or one for the period it is narrowed
   * to (none for every trip); without it the title is left alone.
   */
  tripListTitle?: string | ((period: Period | undefined) => string) | undefined
  /**
   * A Mapbox style URL, or one for each colour scheme. Default: Mapbox Standard, in 3D,
   * by day or by night with the colour scheme.
   */
  mapStyle?: string | Readonly<{ dark: string; light: string }> | undefined
  /** Otherwise read from the page's `data-trip-map-token`. */
  mapboxToken?: string | undefined
  /** Words to replace the Chinese defaults with. */
  messages?: Partial<Messages> | undefined
  /** The tracks a video may be set to. */
  music?: readonly MusicTrack[] | undefined
  /** The map was narrowed to a period, or to none; also as it opens on one. */
  onPeriodChange?: ((period: Period | undefined) => void) | undefined
  /** Something couldn't be fetched or shown; the map carries on without it. */
  onError?: ((error: TripMapError) => void) | undefined
  /** The element holding the map's markup. */
  page: HTMLElement
  /**
   * The fog of war, shown by the `[data-trip-map-fog]` toggle: which regions are lit,
   * `FogOfWar` as JSON (see `buildFogOfWar`), and the regions, GeoJSON features with an
   * `id` each. Default: `/map/fog.json` and `/map/cities.json`.
   */
  fogUrl?: string | undefined
  fogRegionsUrl?: string | undefined
  /** The places to show. Otherwise read from the page's `[data-trip-map-places]` JSON. */
  places?: readonly PlaceMarker[] | undefined
  /** The title over a saved screenshot. Default: the document's title. */
  screenshotTitle?: string | undefined
  /** Where a video's viewers can find the trips, on its summary card. Default: the page's host. */
  siteLine?: string | undefined
  /** The totals for a screenshot's header. Otherwise read from `[data-trip-map-stats]`. */
  stats?: MapStats | undefined
  /** The trip the page opens on, with its data. Otherwise read from `[data-trip-map-trip]`. */
  trip?: TripData | undefined
  /** Every trip, for the overview. Otherwise read from `[data-trip-map-trips]`. */
  trips?: readonly TripSummary[] | undefined
}

// How long the markers take to fade as a trip opens (see styles.css), before they're
// taken off the map.
const MARKER_FADE = 200

/** Sets the map up on `page`. One map per page: its shortcuts listen on the window. */
export function createTripMap(options: TripMapOptions): TripMap {
  const { page } = options
  const messages = resolveMessages(options.messages)
  const urls: Urls = { ...defaultUrls, ...options.urls }
  const elements = collectMapElements(page)
  const {
    canvas,
    fallback,
    fallbackMessage,
    fallbackRetry,
    loading,
    locateButton,
    resetViewButton,
    screenshotButton,
  } = elements
  const controller = new AbortController()
  const { signal } = controller
  const reducedMotion = getReducedMotionQuery()
  // A phone's screen, where the sheets come up from the bottom.
  const narrow = window.matchMedia('(max-width: 48rem)')
  const places = options.places ?? parseJson<PlaceMarker[]>(page, '[data-trip-map-places]', [])
  const trips = options.trips ?? parseJson<TripSummary[]>(page, '[data-trip-map-trips]', [])
  const tripsById = new Map(trips.map((trip) => [trip.id, trip]))
  const inlineTrip =
    options.trip ?? parseJson<TripData | null>(page, '[data-trip-map-trip]', null) ?? undefined
  const stats =
    options.stats ?? parseJson<MapStats | undefined>(page, '[data-trip-map-stats]', undefined)
  const documentTitle = options.documentTitle ?? document.title
  const mapLanguage = options.mapLanguage ?? getMapLanguage(messages.locale)
  const report = (error: TripMapError) => options.onError?.(error)
  let map: mapboxgl.Map | undefined
  let mapLoaded = false
  let markerTimer: ReturnType<typeof setTimeout> | undefined
  let loadTimeout: ReturnType<typeof setTimeout> | undefined
  // Where the map was before a period was picked, to go back to as it's cleared.
  let viewBeforePeriod: ReturnType<MapCamera['getView']>
  // The period picked as a trip's sidebar opened: one picked while it's open is undone
  // as it closes, so closing leaves the trip rather than landing on that period's list.
  let periodBeforeTrip: Period | undefined
  const getLoadedMap = () => (mapLoaded ? map : undefined)

  const style = createMapStyle({
    getMap: getLoadedMap,
    hasSettings: Boolean(elements.settings),
    isNarrow,
    language: mapLanguage,
    page,
    signal,
    style: options.mapStyle,
  })
  const address = createUrlState({
    canPlay: (id) => tripPlayer.has(id),
    hasPlace: (id) => markers.has(id),
    hasTrip: (id) => tripsById.has(id),
    hasTripList: Boolean(elements.tripList),
    places,
    urls,
  })
  const sides = createMapSides({
    elements,
    getMap: () => map,
    getParts: () => ({ drawer, momentList, tripDetails, tripList }),
    isNarrow,
    isTripListUrl: address.isTripList,
  })
  const camera = createMapCamera({
    frameRoute: (id, padding, frameOptions) => tripOverview.frame(id, padding, frameOptions),
    getMap: () => map,
    getShownPlaces: () => markers.getShownPlaces(),
    isLoaded: () => mapLoaded,
    isNarrow,
    isPlaying: () => Boolean(tripPlayer.activeId),
    places,
    reducedMotion,
    sides,
  })

  const tripPlayer = createTripPlayer({
    brand: options.brand,
    carModel: options.carModel,
    fetchTrip: (id) =>
      fetch(urls.getTripDataUrl(id), { signal })
        .then((response) => {
          if (!response.ok) {
            throw new Error(`${response.status} ${response.statusText}`)
          }

          return response.json() as Promise<TripData>
        })
        .catch((cause: unknown) => {
          if (!signal.aborted) {
            report({
              cause,
              id,
              kind: 'trip',
              message: `The data of trip "${id}" couldn't be fetched.`,
            })
          }

          return undefined
        }),
    getMap: () => (mapLoaded ? map : undefined),
    getMapTheme: style.getMapTheme,
    isMapDark: style.isMapDark,
    // The card playing is in the path, `/trip/<id>/play/<stop>/`.
    getStop: () => urls.getTripStop(window.location.pathname),
    inlineTrip,
    messages,
    music: options.music,
    onClose: requestSelectionClose,
    page,
    panel: elements.panel,
    setStop: (tripId, stop) => {
      const path = urls.getTripPath(tripId, stop || undefined, address.isNested())

      if (address.getTripId() === tripId && window.location.pathname !== path) {
        history.replaceState(getHistoryState(), '', `${path}${window.location.search}`)
      }
    },
    shortcuts: elements.shortcuts,
    signal,
    siteLine: options.siteLine ?? window.location.host,
    stage: elements.stage,
    trips,
  })
  const tripOverview = createTripOverview({
    getInk: () => style.getMapTheme().ink,
    getMap: () => map,
    messages,
    // Pointing at a route, or focusing its link, starts loading that trip.
    onHighlight: (id) => tripPlayer.prefetch(id),
    // A route clicked on the map opens its sidebar; a click off the routes closes it.
    onPick: (id) => {
      if (pickingRoute) {
        return
      }

      if (id) {
        openTrip(id)
      } else if (address.getPickedTripId()) {
        requestSelectionClose()
      }
    },
    onSelect: selectTrip,
    reducedMotion,
    signal,
    trips,
  })
  const markers = createPlaceMarkers({
    getAnimationDuration: camera.getAnimationDuration,
    getClearPadding: () => sides.getClearPadding(),
    getMap: () => map,
    isPaused: () => !mapLoaded || Boolean(tripPlayer.activeId) || Boolean(tripScroll?.drawn),
    messages,
    onPrefetch: (id) => void drawer.load(id),
    onSelect: selectPlace,
    places,
    signal,
  })
  const drawer = createPlaceDrawer({
    createLightboxMedia: options.createLightboxMedia,
    elements,
    getPlaceUrl: urls.getPlaceUrl,
    narrow,
    messages,
    onClosed: () => {
      camera.clearDrawerPadding()
      markers.setPressed(undefined)
    },
    onError: (id, cause) =>
      report({
        cause,
        id,
        kind: 'place',
        message: `The posts at place "${id}" couldn't be fetched.`,
      }),
    onOpen: (place, { moveMap }) => {
      markers.setPressed(place.id)
      tripList?.close({ restoreFocus: false, restoreView: false })

      if (moveMap) {
        moveMapToPlace(place)
      }
    },
    // The drawer's place stays in view: it has Moments in every year it lists.
    onPickYear: (year) => pickPeriod({ year }),
    onRestoreFocus: (button) => markers.focusQuietly(button),
    parent: () => momentList?.sheet,
    signal,
  })

  const tripList =
    elements.tripList &&
    createTripList({
      closeButton: elements.tripListClose,
      narrow,
      list: elements.tripList,
      onClose: ({ restoreView }) => {
        tripOverview.highlight(undefined)

        // Every place in view again, now the list is gone; going back puts the map
        // where it was before the list instead (see `restoreView`).
        if (restoreView && map && mapLoaded && camera.framedOnAll) {
          camera.fitAll()
        }
      },
      onHighlight: (id, { frame }) => {
        tripOverview.highlight(id)

        if (id && frame) {
          camera.frameTripBeside(elements.tripList!, id)
        }
      },
      onOpen: openTrip,
      onRequestClose: requestSelectionClose,
      onRequestOpen: () => {
        setSelectionUrl(urls.getTripListPath(markers.period))
        syncSelection()
      },
      openers: elements.tripListOpeners,
      signal,
    })

  const tripDetails =
    elements.tripDetails &&
    elements.tripDetailsContent &&
    createTripDetails({
      closeButton: elements.tripDetailsClose,
      content: elements.tripDetailsContent,
      eyebrow: elements.tripDetailsEyebrow,
      getUrl: urls.getTripMomentsUrl,
      narrow,
      list: elements.tripDetails,
      messages,
      onError: (id, cause) =>
        report({
          cause,
          id,
          kind: 'trip',
          message: `The Moments of trip "${id}" couldn't be fetched.`,
        }),
      onPlace: showPlace,
      onRequestClose: requestSelectionClose,
      signal,
      title: elements.tripDetailsTitle,
    })
  // Reading a trip's sidebar plays it along on the map, as the player would.
  const tripScroll =
    elements.tripDetailsContent &&
    createTripScroll({
      content: elements.tripDetailsContent,
      flyover: tripPlayer.flyover,
      getBlocked: () =>
        [elements.tripDetails, page.querySelector('[data-trip-map-toolbar]')]
          .filter((element) => element && element.getClientRects().length > 0)
          .map((element) => element!.getBoundingClientRect()),
      getMap: () => (mapLoaded ? map : undefined),
      getPadding: () => {
        const side = sides.getSidePadding(elements.tripDetails!)

        return {
          bottom: side.bottom + 32,
          left: side.left + 32,
          right: side.right + 32,
          top: side.top + 16,
        }
      },
      getTheme: style.getMapTheme,
      loadTrip: (id) => tripPlayer.load(id),
      messages,
      onDrawn: (drawn) => {
        // The places give way to the trip's stops, as they do while it plays.
        tripOverview.setPickedRead(drawn)
        fogLayer.setHidden(drawn)
        markers.setHidden(drawn)

        // Back at the top: the whole route in view again.
        if (!drawn && tripDetails?.activeId) {
          camera.frameTripBeside(elements.tripDetails!, tripDetails.activeId)
        }
      },
      reducedMotion,
      signal,
    })
  // Set while the map picks a route itself, as a trip's sidebar opens or closes, or a
  // period hides it.
  let pickingRoute = false

  const momentList =
    elements.moments &&
    elements.momentsContent &&
    createMomentList({
      closeButton: elements.momentsClose,
      content: elements.momentsContent,
      eyebrow: elements.momentsEyebrow,
      getMapPath: (period) => urls.getMapPath(period),
      getMomentsUrl: urls.getMomentsUrl,
      getPlaceCount: () => markers.getShownPlaces().length,
      narrow,
      list: elements.moments,
      messages,
      onError: (year, cause) =>
        report({ cause, kind: 'map', message: `The Moments of ${year} couldn't be fetched.` }),
      onPickMonth: (month) => {
        if (markers.period) {
          pickPeriod({ month, year: markers.period.year })
        }
      },
      onPlace: showPlace,
      onRequestClose: () => pickPeriod(undefined),
      signal,
      title: elements.momentsTitle,
    })

  const fogLayer = createFogLayer({
    areaElement: elements.fogArea,
    button: elements.fogButton,
    getMap: () => (mapLoaded ? map : undefined),
    getTheme: style.getMapTheme,
    messages,
    onError: (cause) =>
      report({ cause, kind: 'map', message: "The fog of war couldn't be fetched." }),
    reducedMotion,
    regionsUrl: options.fogRegionsUrl ?? '/map/cities.json',
    signal,
    url: options.fogUrl ?? '/map/fog.json',
  })

  // A cluster opened is the viewer's own browsing, as a drag is.
  markers.onOpenCluster = camera.moveByHand

  /**
   * Shows the map as `next` has it, and remembers it: another style built again, Standard's
   * own settings changed in place, and the map leaning and projected anew.
   */
  function applySettings(next: MapSettings) {
    style.setSettings(next)
    camera.pitchedByHand = false

    if (!map || !mapLoaded) {
      return
    }

    style.restyle(map)

    if (map.getProjection().name !== next.projection) {
      map.setProjection(next.projection)
    }

    const pitch = getMapPitch(map, map.getZoom())

    // A trip's camera leans with the map itself.
    if (!tripPlayer.activeId && Math.abs(map.getPitch() - pitch) > 0.5) {
      map.easeTo({ duration: camera.getAnimationDuration(), pitch })
    }
  }

  function isNarrow() {
    return narrow.matches
  }

  /**
   * On a phone the legend sits in the row of the map's logo and its attribution, in the
   * room between them, rather than in a row of its own above (see styles.css). Where it
   * doesn't fit, as with a year picked on a narrow phone or the attribution opened, it's
   * raised above them again. Measured by the legend's own width against the room, which
   * doesn't change with where the legend is.
   */
  function placeLegend() {
    const legend = elements.legend
    const container = map?.getContainer()
    const logo = container?.querySelector('.mapboxgl-ctrl-logo')
    const attribution = container?.querySelector('.mapboxgl-ctrl-attrib')

    if (!legend) {
      return
    }

    if (!isNarrow() || !logo || !attribution) {
      legend.removeAttribute('data-trip-map-legend-raised')
      return
    }

    // Folded into its button, the attribution's own box is empty. Moved up out of the
    // logo's row, as on a phone (see styles.css), it leaves the row to the map's edge.
    const logoBox = logo.getBoundingClientRect()
    const attributionBox = attribution.getBoundingClientRect()
    const buttonBox = attribution
      .querySelector('.mapboxgl-ctrl-attrib-button')
      ?.getBoundingClientRect()
    const shown = attributionBox.width > 0 ? attributionBox : (buttonBox ?? attributionBox)
    const inRow = shown.bottom > logoBox.top - legend.offsetHeight && shown.top < logoBox.bottom
    const right = inRow ? shown.left : container!.getBoundingClientRect().right
    const room = right - logoBox.right

    legend.toggleAttribute('data-trip-map-legend-raised', legend.offsetWidth + 16 > room)
  }

  /** Opens a trip's sidebar, its route lifted and framed: not playing yet. */
  function openTrip(id: string) {
    setSelectionUrl(urls.getPickedTripPath(id, address.isNested()))
    syncSelection()
  }

  function moveMapToPlace(place: PlaceMarker) {
    camera.bringPlaceIntoView(place, sides.getSidePadding(elements.drawer))
  }

  /**
   * Fades the markers and the legend out as a trip opens, then takes the markers off
   * the map; closing it, puts them back and fades them in.
   */
  function setTripMode(on: boolean) {
    if (markerTimer) {
      clearTimeout(markerTimer)
      markerTimer = undefined
    }

    fogLayer.setHidden(on)

    if (on) {
      page.dataset.tripMapActive = ''
      markerTimer = setTimeout(
        () => {
          markerTimer = undefined
          markers.setHidden(true)
        },
        reducedMotion.matches ? 0 : MARKER_FADE,
      )
      return
    }

    // Back on the map still faded, then let go of the attribute so they fade in.
    markers.setHidden(false)
    requestAnimationFrame(() => {
      if (!tripPlayer.activeId) {
        delete page.dataset.tripMapActive
      }
    })
  }

  /** The title for what the address shows: a trip, the trip list, a period, or the map. */
  function syncDocumentTitle() {
    // A trip picked in the list is the page's subject as much as one playing.
    const playingId = address.getTripId()
    const id = playingId ?? address.getPickedTripId()
    const title = id ? tripPlayer.getTitle(id) : undefined
    const period = address.isMap() ? markers.period : undefined

    if (id && title !== undefined && options.getDocumentTitle) {
      document.title = options.getDocumentTitle({ id, playing: id === playingId, title })
    } else if (options.getDocumentTitle || options.tripListTitle || options.getPeriodTitle) {
      const { tripListTitle } = options
      document.title =
        (address.isTripList() &&
          (typeof tripListTitle === 'function'
            ? tripListTitle(urls.getTripListPeriod(window.location.pathname))
            : tripListTitle)) ||
        (period && options.getPeriodTitle?.(period)) ||
        documentTitle
    }
  }

  function syncTripFromUrl() {
    const id = address.getTripId()

    syncDocumentTitle()

    if (id) {
      // Set before the map loads too, so its routes are added hidden.
      tripOverview.hide(id)
      page.dataset.tripMapActive = ''
      // Its sidebar's play-along goes first: the player draws on the same layers.
      tripScroll?.stop()

      if (!mapLoaded) {
        return
      }

      if (!tripPlayer.activeId) {
        setTripMode(true)
      }

      tripPlayer.start(id)
      return
    }

    // Also after a trip that failed to load, or was closed before the map loaded.
    if (tripPlayer.activeId || page.dataset.tripMapActive !== undefined) {
      tripPlayer.stop()
      tripOverview.show()
      setTripMode(false)
      return true
    }

    return false
  }

  function syncSelection(selectOptions: { focus?: boolean; moveMap?: boolean } = {}) {
    // Before the rest, so a trip closing frames the places beside the Moments.
    syncMomentList()
    // The legend's links go to the map's addresses or the trip list's, as it's open.
    syncPeriodLinks()

    // A trip closed: every place in view again, beside the list if it's coming back.
    const tripClosed = syncTripFromUrl()
    // Going back puts the map where it was itself, after the rest.
    const moveMap = selectOptions.moveMap !== false

    if (tripPlayer.activeId) {
      drawer.close({ restoreFocus: false })
      tripList?.close({ restoreFocus: false, restoreView: false })
      closeTripDetails()
      return
    }

    const id = address.getPlaceId()
    const place = id ? markers.getPlace(id) : undefined
    const openId = address.getPickedTripId()

    // A trip's sidebar takes the drawer's side of the map, flying to its route.
    if (openId && tripDetails) {
      const trip = tripsById.get(openId)!

      if (!tripDetails.isOpen) {
        periodBeforeTrip = markers.period
      }

      drawer.close({ restoreFocus: false })
      tripList?.close({ restoreFocus: false, restoreView: false })
      tripDetails.show(trip, { focus: selectOptions.focus !== false })
      pickRoute(openId)
      tripScroll?.start(openId)

      if (moveMap) {
        camera.frameTripBeside(elements.tripDetails!, openId)
      }

      return
    }

    closeTripDetails()

    if (moveMap && tripClosed && !address.isTripList()) {
      camera.fitAll()
    }

    if (id && place) {
      const fallbackLink = page.querySelector<HTMLElement>(
        `[data-trip-map-place="${CSS.escape(id)}"]`,
      )

      drawer.open(place, {
        ...selectOptions,
        trigger: markers.getButton(id) ?? fallbackLink ?? undefined,
      })
      return
    }

    // The list takes the drawer's side of the map.
    if (address.isTripList()) {
      const opening = !tripList?.isOpen

      drawer.close({ restoreFocus: false })
      tripList?.show({ focus: selectOptions.focus !== false })

      if (!moveMap) {
        return
      }

      if (tripClosed) {
        camera.fitAll()
      } else if (opening) {
        camera.moveMapFromUnder(elements.tripList!)
      }

      return
    }

    // The place stays where it is as the drawer goes: only more of the map is clear.
    drawer.close({ restoreFocus: selectOptions.focus !== false })
    tripList?.close({ restoreFocus: selectOptions.focus !== false, restoreView: moveMap })
  }

  /** Lifts a trip's route as its sidebar opens, or lets go of it as it closes. */
  function pickRoute(id: string | undefined) {
    pickingRoute = true

    try {
      tripOverview.pick(id)
    } finally {
      pickingRoute = false
    }
  }

  /** Closes a trip's sidebar, letting go of its route. */
  function closeTripDetails() {
    if (!tripDetails?.isOpen) {
      return
    }

    tripDetails.close()
    tripScroll?.stop()
    pickRoute(undefined)
  }

  /**
   * Goes to a selection's address: in place of the one open, or after the map or the
   * trip list, which stays behind what it opened for going back to, keeping where the
   * map was then.
   */
  function setSelectionUrl(url: string) {
    // One trip's sidebar opened from another's replaces it, as a place does a place.
    const replace =
      !address.isTripList() &&
      Boolean(address.getPlaceId() ?? address.getPickedTripId() ?? address.getTripId()) &&
      !(address.getPickedTripId() && urls.getTripId(new URL(url, window.location.href).pathname))
    const { [VIEW_STATE_KEY]: _view, ...currentState } = getHistoryState()
    const nextState = {
      ...currentState,
      [HISTORY_STATE_KEY]: replace ? currentState[HISTORY_STATE_KEY] === true : true,
    }

    if (replace) {
      history.replaceState(nextState, '', url)
    } else {
      history.replaceState({ ...currentState, [VIEW_STATE_KEY]: camera.getView() }, '')
      history.pushState(nextState, '', url)
    }
  }

  function selectPlace(id: string, trigger: HTMLElement) {
    const place = markers.getPlace(id)

    if (!place) {
      return
    }

    drawer.trigger = trigger
    setSelectionUrl(`${urls.getMapPath(markers.period, id)}${window.location.search}`)
    drawer.open(place, { trigger })
  }

  /** Plays a trip, from the card `stop` names (a stop's anchor), or from its start. */
  function selectTrip(id: string, stop?: string) {
    setSelectionUrl(urls.getTripPath(id, stop, address.isNested()))
    syncSelection({ focus: false })
  }

  /**
   * Narrows the map to a year, or a month of one: only the places with Moments then and
   * the trips under way then on it and in the trip list, with the Moments beside it; or
   * everything again. Frames the places; a place open that's left off the map closes.
   */
  function pickPeriod(next: Period | undefined) {
    if (isSamePeriod(next, markers.period)) {
      return
    }

    // Picked from every place, the view to go back to as it's cleared again.
    const viewBefore = markers.period ? undefined : camera.getView()

    markers.setPeriod(next)
    // A route the period hides lets go of it, which isn't a click off the routes.
    pickingRoute = true

    try {
      tripOverview.setPeriod(next)
    } finally {
      pickingRoute = false
    }
    markers.setPressed(drawer.activeId)

    if (elements.tripList) {
      showPeriodRows(elements.tripList, elements.tripYearGroups, tripsById, next)
    }

    syncPeriodLinks()

    elements.legend?.toggleAttribute('data-trip-map-year-picked', next !== undefined)

    // Shown, with whatever holds it, only while a period is picked.
    const clearItem = elements.yearClear?.closest('li') ?? elements.yearClear

    if (clearItem) {
      clearItem.hidden = next === undefined
    }

    if (drawer.activeId && !markers.isShown(drawer.activeId)) {
      requestSelectionClose()
    }

    syncPeriodUrl()
    syncMomentList()
    syncDocumentTitle()
    options.onPeriodChange?.(next)

    // A place open stays the one in view; otherwise the period's places are framed, or
    // cleared, the map is put back where it was before, unless moved by hand since.
    if (mapLoaded && !tripPlayer.activeId && !drawer.activeId) {
      if (!next && camera.isViewCurrent(viewBeforePeriod)) {
        camera.restoreView(viewBeforePeriod)
      } else {
        camera.fitAll()
      }
    }

    viewBeforePeriod = next ? (viewBeforePeriod ?? viewBefore) : undefined
  }

  /** The map's own address, narrowed to the period picked if any, and no place open. */
  function getMapPath() {
    return urls.getMapPath(markers.period)
  }

  /** On the map's own address, or the trip list's, says the period picked, keeping the place open. */
  function syncPeriodUrl() {
    const path = address.isTripList()
      ? urls.getTripListPath(markers.period)
      : urls.getMapPath(markers.period, address.getPlaceId())

    if ((!address.isMap() && !address.isTripList()) || window.location.pathname === path) {
      return
    }

    history.replaceState(getHistoryState(), '', `${path}${window.location.search}`)
  }

  /**
   * The legend's links go where pressing them does. Each year: to that year, or from the
   * year picked to every year again, on the trip list's addresses while it's open. The
   * trip list's openers: to the list for the period picked, or back to the map from it.
   */
  function syncPeriodLinks() {
    const picked = markers.period

    for (const opener of elements.tripListOpeners) {
      if (opener instanceof HTMLAnchorElement) {
        opener.href = address.isTripList() ? getMapPath() : urls.getTripListPath(picked)
      }
    }

    for (const link of elements.yearLinks) {
      const year = Number(link.dataset.tripMapYear)
      const next = picked?.year === year && picked.month === undefined ? undefined : { year }

      link.href = address.isTripList() ? urls.getTripListPath(next) : urls.getMapPath(next)

      if (picked?.year === year) {
        link.setAttribute('aria-current', 'true')
      } else {
        link.removeAttribute('aria-current')
      }
    }
  }

  /**
   * The Moments beside the map while it's narrowed, on its own address: under a place's
   * drawer, and away for the trip list or a trip.
   */
  function syncMomentList() {
    if (markers.period && address.isMap()) {
      momentList?.show(markers.period, { focus: false })
    } else {
      momentList?.close()
    }
  }

  /** Flies the map to a place a Moment in the list was at, and lifts its dot. */
  function showPlace(id: string) {
    const place = markers.getPlace(id)

    if (!place || !map || !mapLoaded) {
      return false
    }

    markers.setPressed(id)
    camera.bringPlaceIntoView(place, sides.getClearPadding())

    return true
  }

  function requestSelectionClose() {
    const openId = address.getPickedTripId()

    if (!drawer.activeId && !tripPlayer.activeId && !address.isTripList() && !openId) {
      return
    }

    if (openId && tripDetails?.isOpen && !isSamePeriod(markers.period, periodBeforeTrip)) {
      pickPeriod(periodBeforeTrip)
    }

    if (getHistoryState()[HISTORY_STATE_KEY]) {
      history.back()
      return
    }

    // A trip's sidebar opened on its own address closes to the list it belongs to.
    history.replaceState(
      getHistoryState(),
      '',
      `${openId && tripList ? urls.getTripListPath(markers.period) : getMapPath()}${window.location.search}`,
    )
    syncSelection()
  }

  function showFallback(message: string, fallbackOptions: { retry?: boolean } = {}) {
    report({ kind: 'map', message })
    loading.hidden = true
    elements.placeholder?.setAttribute('data-trip-map-loaded', '')
    fallbackMessage.textContent = message
    fallbackRetry.hidden = !fallbackOptions.retry
    fallback.hidden = false
    canvas.setAttribute('aria-hidden', 'true')
  }

  function hideFallback() {
    loading.hidden = true
    // The picture standing in for the map fades out from over it.
    elements.placeholder?.setAttribute('data-trip-map-loaded', '')
    fallback.hidden = true
    canvas.removeAttribute('aria-hidden')
  }

  function applyFog() {
    map?.setFog({
      color: 'rgba(0, 0, 0, 0)',
      'high-color': 'rgba(255, 255, 255, 0.08)',
      'horizon-blend': 0.08,
      'space-color': 'rgba(0, 0, 0, 0)',
    })
  }

  /**
   * Saves a picture of the map framed on every place, named and counted, with the
   * totals above it, then puts the view back as it was.
   */
  async function takeScreenshot() {
    if (!map || !mapLoaded || !screenshotButton || screenshotButton.ariaBusy === 'true') {
      return
    }

    const theme = readTheme(page)
    const number = (value: number) => value.toLocaleString(messages.locale)

    screenshotButton.ariaBusy = 'true'
    // Pressed in the settings, say: out of the way of the map it saves.
    screenshotButton.closest('dialog')?.close()
    // The map is framed on every place for the picture, then put back; this covers it.
    loading.textContent = messages.screenshotRendering
    loading.hidden = false

    try {
      const blob = await captureMap(map, {
        formatPlaceCount: (count) => t(messages, 'clusterPlaces', { count }),
        getPlaces: (current) => markers.getScreenshotPlaces(current),
        legend: elements.legend,
        places: camera.getFramedPlaces(),
        site: `${window.location.host}${urls.mapPath}`,
        stats: stats
          ? [
              stats.firstYear === stats.lastYear
                ? String(stats.firstYear)
                : `${stats.firstYear}–${stats.lastYear}`,
              t(messages, 'statPlaces', { count: number(stats.places) }),
              t(messages, 'statMoments', { count: number(stats.moments) }),
              ...(stats.trips > 0 ? [t(messages, 'statTrips', { count: stats.trips })] : []),
              ...(stats.drivingKm > 0
                ? [t(messages, 'statDriving', { count: number(stats.drivingKm) })]
                : []),
            ]
          : [],
        theme: {
          background: getComputedStyle(page).backgroundColor,
          muted: theme.muted,
          text: theme.ink,
        },
        title: options.screenshotTitle ?? document.title,
      })
      const now = new Date()
      const day = [now.getFullYear(), now.getMonth() + 1, now.getDate()]
        .map((part) => String(part).padStart(2, '0'))
        .join('')

      loading.hidden = true
      await savePicture(blob, `map-${day}.png`)
    } finally {
      loading.hidden = true
      screenshotButton.ariaBusy = null
    }
  }

  for (const link of elements.fallbackPlaces) {
    link.addEventListener(
      'click',
      (event) => {
        const id = link.dataset.tripMapPlace

        if (id && isPlainClick(event)) {
          event.preventDefault()
          selectPlace(id, link)
        }
      },
      { signal },
    )
  }

  elements.drawerClose.addEventListener('click', requestSelectionClose, { signal })
  fallbackRetry.addEventListener('click', () => window.location.reload(), { signal })
  screenshotButton?.addEventListener('click', () => void takeScreenshot(), { signal })

  const quickMoves = createQuickMoves({
    camera,
    elements,
    getClearPadding: sides.getClearPadding,
    getMap: getLoadedMap,
    isPlaying: () => Boolean(tripPlayer.activeId),
    messages,
    onError: (cause) =>
      report({ cause, kind: 'map', message: "The viewer's position couldn't be found." }),
    signal,
  })

  // The Moments the drawer and the lists fetch link their trips, opening their sidebars,
  // and a trip's sidebar plays it, from its start or a stop's card; the drawer's link
  // the other places they were at.
  page.addEventListener(
    'click',
    (event) => {
      if (!isPlainClick(event)) {
        return
      }

      const target = event.target instanceof Element ? event.target : null
      const trip = target?.closest<HTMLElement>('[data-trip-map-trip-pick]')
      const play = target?.closest<HTMLElement>('[data-trip-map-stop-play]')
      const place = target?.closest<HTMLElement>('[data-trip-map-place-link]')
      const inSide = (element: Element) =>
        [elements.drawer, elements.moments, elements.tripDetails].some((side) =>
          side?.contains(element),
        )
      const tripId = trip?.dataset.tripMapTripPick
      const playId = play?.dataset.tripMapStopPlay

      if (play && playId && inSide(play) && tripPlayer.has(playId)) {
        event.preventDefault()
        selectTrip(playId, play.dataset.tripMapStop)
      } else if (trip && tripId && inSide(trip) && tripDetails && tripsById.has(tripId)) {
        event.preventDefault()
        openTrip(tripId)
      } else if (place?.dataset.tripMapPlaceLink && elements.drawer.contains(place)) {
        const button = markers.getButton(place.dataset.tripMapPlaceLink)

        if (button) {
          event.preventDefault()
          selectPlace(place.dataset.tripMapPlaceLink, button)
        }
      }
    },
    { signal },
  )
  // Pointing at a Moment's trip lifts its route and starts loading it.
  page.addEventListener(
    'pointerover',
    (event) => {
      const stop = (event.target as Element | null)?.closest<HTMLElement>(
        '[data-trip-map-stop-link]',
      )
      const id = stop?.dataset.tripMapStopLink

      if (id && !tripPlayer.activeId && tripPlayer.has(id)) {
        tripPlayer.prefetch(id)
        tripOverview.highlight(id)
      }
    },
    { signal },
  )
  page.addEventListener(
    'pointerout',
    (event) => {
      const from = (event.target as Element | null)?.closest('[data-trip-map-trip-pick]')
      const to = (event.relatedTarget as Element | null)?.closest?.('[data-trip-map-trip-pick]')

      if (from && from !== to && !tripPlayer.activeId) {
        tripOverview.highlight(undefined)
      }
    },
    { signal },
  )

  // The trip list's rows are the list's own: picked first, then played.
  const toolbarTripLinks = elements.tripLinks.filter((link) => !elements.tripList?.contains(link))

  for (const [linkIndex, link] of toolbarTripLinks.entries()) {
    const id = link.dataset.tripMapTripLink!

    link.addEventListener(
      'focus',
      () => {
        // The one Tab comes back to.
        for (const other of toolbarTripLinks) {
          other.tabIndex = other === link ? 0 : -1
        }

        tripOverview.highlight(id)
        tripOverview.reveal(id, sides.getClearPadding())
      },
      { signal },
    )
    link.addEventListener(
      'keydown',
      (event) => {
        const last = toolbarTripLinks.length - 1
        const next = {
          ArrowLeft: Math.max(linkIndex - 1, 0),
          ArrowRight: Math.min(linkIndex + 1, last),
          End: last,
          Home: 0,
        }[event.key]

        if (next !== undefined) {
          event.preventDefault()
          toolbarTripLinks[next]?.focus()
        }
      },
      { signal },
    )
    link.addEventListener('blur', () => tripOverview.highlight(undefined), { signal })
    link.addEventListener(
      'click',
      (event) => {
        if (isPlainClick(event)) {
          event.preventDefault()
          selectTrip(id)
        }
      },
      { signal },
    )
  }

  // The period stays as picked going back and forward; the address follows it.
  window.addEventListener(
    'popstate',
    () => {
      // Back to another page altogether, which takes the map's place: what's open stays
      // as it is, for that page to come in over, rather than closing on the way out.
      if (!isOwnPath(urls, window.location.pathname)) {
        return
      }

      // Back to an address the map was moved from, and not moved by hand since: the map
      // goes back to where it was there, rather than framing what's open anew.
      const view = getHistoryState()[VIEW_STATE_KEY]
      const restore = camera.isViewCurrent(view) && mapLoaded

      syncSelection({ moveMap: !restore })
      syncPeriodUrl()

      if (restore) {
        camera.restoreView(view)
      }
    },
    { signal },
  )
  /** Brings what's open back clear of what's beside it, the window's width changed. */
  function keepClearOnResize() {
    // A trip's own camera has the map.
    if (!mapLoaded || tripPlayer.activeId) {
      return
    }

    const place = drawer.activeId ? markers.getPlace(drawer.activeId) : undefined
    const list = sides.getOpenList()

    if (place) {
      moveMapToPlace(place)
    } else if (tripDetails?.activeId && list === elements.tripDetails) {
      // Read along, the sidebar's camera follows the route itself.
      if (!tripScroll?.drawn) {
        camera.frameTripBeside(elements.tripDetails!, tripDetails.activeId)
      }
    } else if (list) {
      camera.moveMapFromUnder(list)
    } else if (camera.framedOnAll) {
      // A phone turned sideways, or a window narrowed: every place back in view.
      camera.fitAll()
    }
  }

  // The map follows its container's size by itself. What's open is brought back clear of
  // what's beside it once a resize settles, and only when the width changed: a phone's
  // address bar showing and hiding only changes the height, and shouldn't keep moving
  // the map.
  let resizeTimer: ReturnType<typeof setTimeout> | undefined
  let lastWidth = window.innerWidth

  window.addEventListener(
    'resize',
    () => {
      clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        if (window.innerWidth !== lastWidth) {
          keepClearOnResize()
        }

        lastWidth = window.innerWidth
      }, 150)
    },
    { signal },
  )
  signal.addEventListener('abort', () => clearTimeout(resizeTimer))
  window.addEventListener(
    'keydown',
    (event) => {
      if (
        event.key === 'Escape' &&
        !event.defaultPrevented &&
        (drawer.activeId || tripPlayer.activeId || tripList?.isOpen || tripDetails?.isOpen) &&
        !drawer.lightboxOpen
      ) {
        requestSelectionClose()
      } else if (event.key === 'Escape' && !event.defaultPrevented && tripOverview.pickedId) {
        // A route picked on the map, with nothing open: let go of it.
        tripOverview.pick(undefined)
      } else if (
        event.key === 'Escape' &&
        !event.defaultPrevented &&
        markers.period &&
        !drawer.lightboxOpen
      ) {
        // Then the period picked: every place and trip again, and the Moments away.
        pickPeriod(undefined)
      }
    },
    { signal },
  )
  createMapSettings({
    dialog: elements.settings,
    getDefaults: style.getDefaultSettings,
    getSettings: style.getSettings,
    narrow,
    onChange: applySettings,
    openers: elements.settingsOpeners,
    // The sheet in front, which the settings open over.
    parent: () =>
      [drawer.sheet, tripDetails?.sheet, tripList?.sheet, momentList?.sheet].find(
        (sheet) => sheet?.isOpen,
      ),
    signal,
  })

  address.upgradeHash()
  syncSelection({ focus: true, moveMap: false })

  // What the page was rendered with open, or opened for the address, has been drawn by
  // the next frame but one; only what opens after that comes in (see styles.css).
  let settleFrame = requestAnimationFrame(() => {
    settleFrame = requestAnimationFrame(() => {
      page.dataset.tripMapSettled = ''
    })
  })
  signal.addEventListener('abort', () => {
    cancelAnimationFrame(settleFrame)
    delete page.dataset.tripMapSettled
  })

  // The address may narrow the map to a period to open on.
  pickPeriod(address.getPeriod())

  // A year in the legend, pressed, narrows the map to it; pressed again, every place
  // again, or the whole year from one of its months.
  for (const link of elements.yearLinks) {
    link.addEventListener(
      'click',
      (event) => {
        if (!isPlainClick(event)) {
          return
        }

        const year = Number(link.dataset.tripMapYear)
        const picked = markers.period

        event.preventDefault()
        pickPeriod(picked?.year === year && picked.month === undefined ? undefined : { year })
      },
      { signal },
    )
  }

  elements.yearClear?.addEventListener(
    'click',
    () => {
      pickPeriod(undefined)
      elements.yearLinks[0]?.focus()
    },
    { signal },
  )

  const token = options.mapboxToken ?? page.dataset.tripMapToken?.trim()

  if (!token) {
    showFallback(messages.mapNoToken)
  } else if (places.length === 0) {
    showFallback(messages.mapNoPlaces)
  } else {
    try {
      mapboxgl.accessToken = token
      const openingTripId = address.getPickedTripId()
      const view = camera.getOpeningView(openingTripId ? tripsById.get(openingTripId) : undefined)
      const mapStyle = style.getStyle()

      style.markApplied()

      // Opens already framed on the places, or a trip's page on its route, rather than
      // on the globe and then flying in. It opens flat, as the picture standing in for it
      // is, and leans back once loaded.
      map = new mapboxgl.Map({
        // Beside the trip list, on its page.
        bounds: view.bounds,
        fitBoundsOptions: { ...view.fitBoundsOptions, pitch: 0 },
        // Drawn after the trip player's camera and route have moved in the frame.
        animationFrameProvider: mapFrames,
        attributionControl: false,
        container: canvas,
        dragRotate: true,
        projection: style.getSettings().projection,
        style: mapStyle.url,
        ...(mapStyle.config && { config: mapStyle.config }),
        // Standard puts its names in the language itself; another style has the plugin.
        ...(mapLanguage && { language: mapLanguage }),
        // Chinese, Japanese and Korean names in the style's own glyphs, fetched: drawn here
        // instead, each new one costs the main thread a frame as the names come into view.
        localIdeographFontFamily: false as unknown as string,
        locale: {
          'AttributionControl.ToggleAttribution': messages.mapAttribution,
          'LogoControl.Title': messages.mapLogo,
          'Map.Title': messages.mapTitle,
        },
        touchPitch: true,
      })
      setMapPitch(map, mapStyle.pitch)

      // The attribution stays folded into its ⓘ button at every width, opening on a click.
      map.addControl(new mapboxgl.AttributionControl({ compact: true }))

      // The legend beside the logo as long as it fits there: measured again as it or the
      // map changes size, and as the attribution opens or folds.
      placeLegend()

      if (elements.legend && typeof ResizeObserver === 'function') {
        const legendRoom = new ResizeObserver(() => placeLegend())
        const attributionChange = new MutationObserver(() => placeLegend())

        legendRoom.observe(elements.legend)
        legendRoom.observe(canvas)
        attributionChange.observe(canvas.querySelector('.mapboxgl-control-container') ?? canvas, {
          attributeFilter: ['class'],
          subtree: true,
        })
        signal.addEventListener('abort', () => {
          legendRoom.disconnect()
          attributionChange.disconnect()
        })
      }

      style.syncLanguage(map, !mapStyle.config)

      // Resizing clears the canvas: drawn again in the very next frame, or it flickers.
      map.on('resize', drawNextFrame)

      map.on('style.load', () => {
        syncBasemapRoads(map!, true)
        applyFog()
        // Under the routes and the player's layers, once the map has loaded.
        fogLayer.add()
        tripOverview.add()
        tripScroll?.add()
        tripPlayer.restoreLayers()

        if (tripPlayer.activeId) {
          markers.setHidden(true)
        }
      })
      map.on('click', (event) => {
        if (tripOverview.click(event.point, event.lngLat)) {
          return
        }

        if (drawer.activeId) {
          requestSelectionClose()
        }
      })
      // A drag, a scroll or a key leaves the framing. (Mapbox starts a move of its own
      // on a window resize, carrying the resize event.)
      map.on('movestart', (event) => {
        const input = (event as { originalEvent?: Event }).originalEvent

        if (
          input instanceof MouseEvent ||
          input instanceof KeyboardEvent ||
          (typeof TouchEvent !== 'undefined' && input instanceof TouchEvent)
        ) {
          camera.moveByHand()
        }
      })
      // A scroll's zoom may carry no event at all, as one notch of a mouse's wheel the map
      // can't tell from a trackpad's at once does: the wheel itself tells it.
      map.on('wheel', camera.moveByHand)
      quickMoves.attach(map)
      // The viewer's own zooming leans the map as its framing does, the closer the further,
      // once each zoom has landed: leaning it midway would stop a scroll's own easing.
      map.on('pitchstart', (event) => {
        if (isByViewer(event)) {
          camera.pitchedByHand = true
        }
      })
      map.on('zoomend', (event) => {
        if (isByViewer(event)) {
          camera.leanToZoom()
        }
      })
      map.on('zoom', () => syncBasemapRoads(map!))
      map.on('idle', () => markers.sync())
      map.on('moveend', () => markers.sync())
      // A move keeps the places coming in as it goes, and a zoom's clusters parting or
      // joining as it passes each zoom, rather than all at once as it lands.
      let moveFrame: number | undefined
      map.on('move', () => {
        if (moveFrame !== undefined) {
          return
        }

        moveFrame = requestAnimationFrame(() => {
          moveFrame = undefined
          markers.sync({ settled: false })
        })
      })
      signal.addEventListener('abort', () => {
        if (moveFrame !== undefined) {
          cancelAnimationFrame(moveFrame)
        }
      })
      map.on('load', () => {
        mapLoaded = true

        for (const button of [screenshotButton, resetViewButton, locateButton]) {
          if (button) {
            button.disabled = false
          }
        }

        if (loadTimeout) {
          clearTimeout(loadTimeout)
          loadTimeout = undefined
        }

        hideFallback()

        // The theme changed while the map loaded.
        if (style.isStale) {
          style.setStyle(map!)
        }

        applyFog()
        fogLayer.add()
        markers.sync()

        const urlPlaceId = address.getPlaceId()
        const urlPlace = urlPlaceId ? markers.getPlace(urlPlaceId) : undefined
        const pickedTripId = address.getPickedTripId()

        if (address.getTripId()) {
          syncTripFromUrl()
        } else if (pickedTripId) {
          // Opened on flat, leaning back now.
          camera.frameTripBeside(elements.tripDetails!, pickedTripId, { lean: true })
        } else if (urlPlaceId && urlPlace) {
          drawer.open(urlPlace, { focus: false, trigger: markers.getButton(urlPlaceId) })
        } else {
          camera.fitAll()
        }
      })
      map.on('error', () => {
        if (!mapLoaded) {
          showFallback(messages.mapLoadFailed, { retry: true })
        }
      })

      // A slow connection gets a word that the map is still coming, and only much
      // later the list of places with a way to try again.
      loadTimeout = setTimeout(() => {
        if (!mapLoaded) {
          loading.textContent = messages.mapLoadingSlow
          // A page may start with the line hidden, under a picture of the map.
          loading.hidden = false
          loadTimeout = setTimeout(() => {
            if (!mapLoaded) {
              showFallback(messages.mapLoadTimeout, { retry: true })
            }
          }, 30_000)
        }
      }, 10_000)
    } catch {
      map?.remove()
      canvas.replaceChildren()
      showFallback(messages.mapInitFailed)
    }
  }

  let destroyed = false

  return {
    get activeTripId() {
      return tripPlayer.activeId
    },
    destroy() {
      if (destroyed) {
        return
      }

      destroyed = true
      controller.abort()

      if (loadTimeout) {
        clearTimeout(loadTimeout)
      }

      if (markerTimer) {
        clearTimeout(markerTimer)
      }

      markers.destroy()
      map?.remove()
      canvas.replaceChildren()
    },
    page,
  }
}

/** Whether a move came from the viewer's own input, rather than the package's moves. */
function isByViewer(event: object) {
  return 'originalEvent' in event && Boolean(event.originalEvent)
}
