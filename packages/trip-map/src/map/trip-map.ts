import MapboxLanguage from '@mapbox/mapbox-gl-language'
import mapboxgl, { type PaddingOptions } from 'mapbox-gl'
import { collectMapElements } from '../elements.ts'
import type { Brand, MusicTrack } from '../markup.ts'
import { resolveMessages, t, type Messages } from '../messages.ts'
import { parseJson } from '../player/dom.ts'
import { getReducedMotionQuery } from '../player/motion.ts'
import { createTripOverview } from '../player/overview.ts'
import { createTripPlayer } from '../player/player.ts'
import type { MapStats, PlaceMarker, TripData, TripSummary } from '../player/types.ts'
import { readTheme } from '../dom-theme.ts'
import { createFogLayer } from './fog-layer.ts'
import { createPlaceDrawer, type LightboxMedia } from './place-drawer.ts'
import { createMomentList } from './moment-list.ts'
import { isSamePeriod, overlapsPeriod, type Period } from './period.ts'
import { CLUSTER_MAX_ZOOM, createPlaceMarkers, getAllPlacesView } from './place-markers.ts'
import { captureMap, savePicture } from './screenshot.ts'
import { createTripList } from './trip-list.ts'
import {
  defaultUrls,
  getHistoryState,
  HISTORY_STATE_KEY,
  parseHash,
  type Urls,
} from './selection-url.ts'

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
  /** The document title while a trip plays; without it the title is left alone. */
  getDocumentTitle?: ((trip: { id: string; title: string }) => string) | undefined
  /**
   * The language the basemap's names are shown in, as @mapbox/mapbox-gl-language takes
   * it (`en`, `ja`, `zh-Hans`…); `false` leaves the basemap as it is. Default: from
   * the messages' locale.
   */
  mapLanguage?: string | false | undefined
  /**
   * What the addresses mean and where things are fetched from, any of `defaultUrls`:
   * `/map/[<year>/[<month>/]][place/<id>/]`, `/trip/[<id>/]`, `/trip/<id>/play/[<stop>/]`,
   * and the fragments and data under them.
   */
  urls?: Partial<Urls> | undefined
  /** The document title while the trip list is open; without it the title is left alone. */
  tripListTitle?: string | undefined
  /** A Mapbox style URL, or one for each colour scheme. Default: Mapbox's light and dark. */
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
// The least room, in pixels, the map frames places in beside the trip list.
const MIN_FRAMED = 240

/** Sets the map up on `page`. One map per page: its shortcuts listen on the window. */
export function createTripMap(options: TripMapOptions): TripMap {
  const { page } = options
  const messages = resolveMessages(options.messages)
  const urls: Urls = { ...defaultUrls, ...options.urls }
  const elements = collectMapElements(page)
  const { canvas, fallback, fallbackMessage, fallbackRetry, loading, screenshotButton } = elements
  const controller = new AbortController()
  const { signal } = controller
  const reducedMotion = getReducedMotionQuery()
  const darkMode = window.matchMedia('(prefers-color-scheme: dark)')
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
  // The style the map was last given, to catch a theme change made while it loaded.
  let mapStyle = getMapStyle()
  // Whether the view is still the one framing every place, untouched since: only then
  // does a change of width frame them again.
  let framedOnAll = true
  // Where the map was before the trip list first flew it to a route, to go back to as
  // the list closes.
  let viewBeforeList: { camera: mapboxgl.CameraOptions; framedOnAll: boolean } | undefined

  const tripPlayer = createTripPlayer({
    brand: options.brand,
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
    // The card playing is in the path, `/trip/<id>/play/<stop>/`.
    getStop: () => urls.getTripStop(window.location.pathname),
    inlineTrip,
    messages,
    music: options.music,
    onClose: requestSelectionClose,
    page,
    panel: elements.panel,
    setStop: (tripId, stop) => {
      const path = urls.getTripPath(tripId, stop || undefined, isNestedTripUrl())

      if (getUrlTripId() === tripId && window.location.pathname !== path) {
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
    getInk: () => readTheme(page).ink,
    getMap: () => map,
    messages,
    // Pointing at a route, or focusing its link, starts loading that trip.
    onHighlight: (id) => tripPlayer.prefetch(id),
    // A route picked on the map picks its row in the list.
    onPick: (id) => tripList?.pick(id),
    onSelect: selectTrip,
    reducedMotion,
    signal,
    trips,
  })
  const markers = createPlaceMarkers({
    getAnimationDuration,
    // The index source is added as the style loads, before the map has loaded.
    getMap: () => map,
    isPaused: () => !mapLoaded || Boolean(tripPlayer.activeId),
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
    isNarrow,
    messages,
    onClosed: () => {
      clearDrawerPadding()
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
    reducedMotion,
    signal,
  })

  const tripList =
    elements.tripList &&
    createTripList({
      closeButton: elements.tripListClose,
      isNarrow,
      list: elements.tripList,
      onClose: ({ restoreView }) => {
        tripOverview.highlight(undefined)

        // Back to where the map was before the list moved it, unless it has been moved
        // by hand since: every place in view again, now the list is gone, or the view
        // the list moved aside.
        if (restoreView && map && mapLoaded) {
          if (viewBeforeList ? viewBeforeList.framedOnAll : framedOnAll) {
            fitAll()
          } else if (viewBeforeList) {
            map.easeTo({ ...viewBeforeList.camera, duration: getAnimationDuration() })
          }
        }

        viewBeforeList = undefined
      },
      onHighlight: (id, { frame }) => {
        tripOverview.highlight(id)

        if (id && frame) {
          frameTripBesideList(id)
        }
      },
      // The address says the trip picked, to come back to it picked.
      onPick: (id) => {
        tripOverview.pick(id)

        if (isTripListUrl()) {
          history.replaceState(
            getHistoryState(),
            '',
            `${id ? urls.getPickedTripPath(id, isNestedTripUrl()) : urls.tripListPath}${window.location.search}`,
          )
          syncDocumentTitle()
        }
      },
      onPlay: selectTrip,
      onRequestClose: requestSelectionClose,
      onRequestOpen: () => {
        setSelectionUrl(urls.tripListPath)
        syncSelection()
      },
      openers: elements.tripListOpeners,
      reducedMotion,
      signal,
    })

  const momentList =
    elements.moments &&
    elements.momentsContent &&
    createMomentList({
      closeButton: elements.momentsClose,
      content: elements.momentsContent,
      eyebrow: elements.momentsEyebrow,
      getMomentsUrl: urls.getMomentsUrl,
      getPlaceCount: () => markers.getShownPlaces().length,
      isNarrow,
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
      reducedMotion,
      signal,
      title: elements.momentsTitle,
    })

  const fogLayer = createFogLayer({
    areaElement: elements.fogArea,
    button: elements.fogButton,
    getMap: () => (mapLoaded ? map : undefined),
    getTheme: () => readTheme(page),
    messages,
    onError: (cause) =>
      report({ cause, kind: 'map', message: "The fog of war couldn't be fetched." }),
    reducedMotion,
    regionsUrl: options.fogRegionsUrl ?? '/map/cities.json',
    signal,
    url: options.fogUrl ?? '/map/fog.json',
  })

  markers.onOpenCluster = () => {
    framedOnAll = false
  }

  function getMapStyle() {
    const style = options.mapStyle ?? {
      dark: 'mapbox://styles/mapbox/dark-v11',
      light: 'mapbox://styles/mapbox/light-v11',
    }

    return typeof style === 'string' ? style : darkMode.matches ? style.dark : style.light
  }

  function isNarrow() {
    return window.matchMedia('(max-width: 48rem)').matches
  }

  /**
   * Older addresses kept a selection in the hash: a place on the map (`/map/#<id>`), or
   * the card a trip played from (`/trip/<id>/#<stop>`). Puts it in the path instead.
   */
  function upgradeHashUrl() {
    const id = parseHash(window.location.hash)
    const { pathname, search } = window.location
    const pickedTripId = urls.getPickedTripId(pathname)
    let path: string | undefined

    if (!id) {
      return
    }

    if (urls.isMapPath(pathname) && markers.has(id)) {
      path = urls.getMapPath(urls.getPeriod(pathname), id)
    } else if (pickedTripId && tripsById.has(pickedTripId)) {
      path = urls.getTripPath(pickedTripId, id, urls.isNestedTripPath(pathname))
    }

    if (path) {
      history.replaceState(getHistoryState(), '', `${path}${search}`)
    }
  }

  /** The place whose drawer the address opens, on the map. */
  function getUrlPlaceId() {
    const id = urls.getPlaceId(window.location.pathname)
    return id && markers.has(id) ? id : undefined
  }

  /** The trip the trip list's address picks, `/trip/<id>/`. */
  function getUrlPickedTripId() {
    const id = urls.getPickedTripId(window.location.pathname)
    return id && tripsById.has(id) ? id : undefined
  }

  /** Flies to a trip's route beside the trip list. */
  function frameTripBesideList(id: string) {
    if (!map || !mapLoaded || !elements.tripList) {
      return
    }

    rememberViewBeforeList()
    framedOnAll = false

    // Wider than for a place, for the route's name above it and the legend below.
    const side = getSidePadding(elements.tripList)

    tripOverview.frame(id, {
      bottom: side.bottom + 64,
      left: side.left + 48,
      right: side.right + 48,
      top: side.top + 32,
    })
  }

  /** Opens the trip list with a trip picked, its route lifted and framed: not playing yet. */
  function showTripInList(id: string) {
    setSelectionUrl(urls.getPickedTripPath(id, isNestedTripUrl()))
    syncSelection()
  }

  function rememberViewBeforeList() {
    if (!map || viewBeforeList) {
      return
    }

    viewBeforeList = {
      camera: {
        bearing: map.getBearing(),
        center: map.getCenter(),
        padding: map.getPadding(),
        pitch: map.getPitch(),
        zoom: map.getZoom(),
      },
      framedOnAll,
    }
  }

  /** What of the map a list beside it covers: the trip list on its address, or the Moments. */
  function getListInset(): { bottom?: number; right?: number } {
    const list =
      elements.tripList && isTripListUrl() && !elements.tripList.hidden
        ? elements.tripList
        : momentList?.isOpen
          ? elements.moments
          : undefined

    if (!list) {
      return {}
    }

    // The list and its margin, at the bottom on a phone and on the right otherwise;
    // nothing if what it leaves is too small to frame anything in.
    const inset: { bottom?: number; right?: number } = isNarrow()
      ? { bottom: list.offsetHeight + 12 }
      : { right: list.offsetWidth + 16 }
    const container = map?.getContainer() ?? canvas
    const room = Math.min(
      container.clientWidth - (inset.right ?? 0),
      container.clientHeight - (inset.bottom ?? 0),
    )

    return room < MIN_FRAMED ? {} : inset
  }

  /** What framing every place keeps clear of: a list beside the map, and the legend. */
  function getFramingInset() {
    const legend = elements.legend?.getBoundingClientRect()
    const container = (map?.getContainer() ?? canvas).getBoundingClientRect()

    return {
      ...getListInset(),
      ...(legend && legend.height > 0 ? { legend: container.bottom - legend.top } : {}),
    }
  }

  /**
   * Moves the map out from under the list as it opens: every place framed beside it, if
   * they were in view, or else the view slid aside by half the list.
   */
  function moveMapForTripList() {
    if (!map || !mapLoaded) {
      return
    }

    if (framedOnAll) {
      fitAll()
      return
    }

    const { bottom = 0, right = 0 } = getListInset()

    rememberViewBeforeList()
    map.panBy([right / 2, bottom / 2], { duration: getAnimationDuration() })
  }

  /** Whether the address leads to its trip by year and the rest; trips opened from it do too. */
  function isNestedTripUrl() {
    return urls.isNestedTripPath(window.location.pathname)
  }

  function isTripListUrl() {
    return (
      Boolean(tripList) &&
      (window.location.pathname === urls.tripListPath || getUrlPickedTripId() !== undefined)
    )
  }

  function getUrlTripId() {
    const id = urls.getTripId(window.location.pathname)
    return id && tripPlayer.has(id) ? id : undefined
  }

  /** Room kept on the map for what sits on its side: the drawer, or the trip list. */
  function getSidePadding(side: HTMLElement = elements.drawer): Required<PaddingOptions> {
    if (isNarrow()) {
      return {
        bottom: Math.min(side.offsetHeight + 32, window.innerHeight * 0.82),
        left: 32,
        right: 32,
        top: 80,
      }
    }

    return { bottom: 32, left: 32, right: side.offsetWidth + 32, top: 80 }
  }

  function getAnimationDuration() {
    return reducedMotion.matches ? 0 : 500
  }

  function moveMapToPlace(place: PlaceMarker) {
    if (!map || !mapLoaded) {
      return
    }

    framedOnAll = false
    map.easeTo({
      center: [place.longitude, place.latitude],
      duration: getAnimationDuration(),
      padding: getSidePadding(),
      zoom: Math.max(map.getZoom(), CLUSTER_MAX_ZOOM + 1),
    })
  }

  /**
   * Opening a place leaves the map padded for the drawer, which would keep later moves
   * (a cluster opening, say) off centre once it's gone. Drops the padding without
   * moving what's on screen.
   */
  function clearDrawerPadding() {
    // A trip's camera sets its own padding every frame.
    if (!map || !mapLoaded || tripPlayer.activeId) {
      return
    }

    const container = map.getContainer()
    const center = map.unproject([container.clientWidth / 2, container.clientHeight / 2])

    map.jumpTo({ center, padding: { bottom: 0, left: 0, right: 0, top: 0 } })
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
    const id = getUrlTripId() ?? getUrlPickedTripId()
    const title = id ? tripPlayer.getTitle(id) : undefined
    const period = isMapUrl() ? markers.period : undefined

    if (id && title !== undefined && options.getDocumentTitle) {
      document.title = options.getDocumentTitle({ id, title })
    } else if (options.getDocumentTitle || options.tripListTitle || options.getPeriodTitle) {
      document.title =
        (isTripListUrl() && options.tripListTitle) ||
        (period && options.getPeriodTitle?.(period)) ||
        documentTitle
    }
  }

  function syncTripFromUrl() {
    const id = getUrlTripId()

    syncDocumentTitle()

    if (id) {
      // Set before the map loads too, so its routes are added hidden.
      tripOverview.hide(id)
      page.dataset.tripMapActive = ''

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

    // A trip closed: every place in view again, beside the list if it's coming back.
    const tripClosed = syncTripFromUrl()

    if (tripPlayer.activeId) {
      drawer.close({ restoreFocus: false })
      tripList?.close({ restoreFocus: false, restoreView: false })
      return
    }

    const id = getUrlPlaceId()
    const place = id ? markers.getPlace(id) : undefined

    if (tripClosed && !isTripListUrl()) {
      fitAll()
    }

    if (id && place) {
      const fallbackButton = page.querySelector<HTMLButtonElement>(
        `[data-trip-map-place="${CSS.escape(id)}"]`,
      )

      drawer.open(place, {
        ...selectOptions,
        trigger: markers.getButton(id) ?? fallbackButton ?? undefined,
      })
      return
    }

    // The list takes the drawer's side of the map.
    if (isTripListUrl()) {
      const opening = !tripList?.isOpen

      drawer.close({ restoreFocus: false })
      tripList?.show({ focus: selectOptions.focus !== false })

      if (tripClosed) {
        fitAll()
      } else if (opening) {
        moveMapForTripList()
      }

      // After the list's own move: the trip its address picks takes the map.
      const pickedId = getUrlPickedTripId()

      if (pickedId) {
        tripList?.pick(pickedId)
      }

      return
    }

    drawer.close({ restoreFocus: selectOptions.focus !== false })
    tripList?.close({ restoreFocus: selectOptions.focus !== false })
  }

  /**
   * Goes to a selection's address: in place of the one open, or after the map or the
   * trip list, which stays behind what it opened for going back to.
   */
  function setSelectionUrl(url: string) {
    const replace = !isTripListUrl() && Boolean(getUrlPlaceId() ?? getUrlTripId())
    const currentState = getHistoryState()
    const nextState = {
      ...currentState,
      [HISTORY_STATE_KEY]: replace ? currentState[HISTORY_STATE_KEY] === true : true,
    }

    if (replace) {
      history.replaceState(nextState, '', url)
    } else {
      history.pushState(nextState, '', url)
    }
  }

  function selectPlace(id: string, trigger: HTMLButtonElement) {
    const place = markers.getPlace(id)

    if (!place) {
      return
    }

    drawer.trigger = trigger
    setSelectionUrl(`${urls.getMapPath(markers.period, id)}${window.location.search}`)
    drawer.open(place, { trigger })
  }

  function selectTrip(id: string) {
    setSelectionUrl(urls.getTripPath(id, undefined, isNestedTripUrl()))
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

    markers.setPeriod(next)
    tripOverview.setPeriod(next)
    markers.setPressed(drawer.activeId)

    if (elements.tripList) {
      for (const link of elements.tripList.querySelectorAll<HTMLElement>(
        '[data-trip-map-trip-link]',
      )) {
        const trip = tripsById.get(link.dataset.tripMapTripLink!)
        const row = link.closest('li') ?? link

        row.hidden = Boolean(next && trip && !overlapsPeriod(trip.start, trip.end, next))
      }

      // A year's group of trips goes with its last one.
      for (const group of elements.tripYearGroups) {
        group.hidden = [...group.querySelectorAll<HTMLElement>('[data-trip-map-trip-link]')].every(
          (link) => (link.closest('li') ?? link).hidden,
        )
      }
    }

    for (const button of elements.yearButtons) {
      button.setAttribute('aria-pressed', String(Number(button.dataset.tripMapYear) === next?.year))
    }

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

    // A place open stays the one in view; otherwise the period's places are framed.
    if (mapLoaded && !tripPlayer.activeId && !drawer.activeId) {
      fitAll()
    }
  }

  /** The period an address narrows the map to, if the map has places in its year. */
  function getUrlPeriod() {
    const period = urls.getPeriod(window.location.pathname)

    return period && places.some((place) => place.years.includes(period.year)) ? period : undefined
  }

  function isMapUrl() {
    const period = urls.getPeriod(window.location.pathname)

    return (
      urls.isMapPath(window.location.pathname) &&
      (!period || places.some((place) => place.years.includes(period.year)))
    )
  }

  /** The map's own address, narrowed to the period picked if any, and no place open. */
  function getMapPath() {
    return urls.getMapPath(markers.period)
  }

  /** On the map's own address, says the period picked, keeping the place open. */
  function syncPeriodUrl() {
    const path = urls.getMapPath(markers.period, getUrlPlaceId())

    if (!isMapUrl() || window.location.pathname === path) {
      return
    }

    history.replaceState(getHistoryState(), '', `${path}${window.location.search}`)
  }

  /**
   * The Moments beside the map while it's narrowed, on its own address: under a place's
   * drawer, and away for the trip list or a trip.
   */
  function syncMomentList() {
    if (markers.period && isMapUrl()) {
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

    // To the middle of what the list leaves of the map.
    const { bottom = 0, right = 0 } = getListInset()

    framedOnAll = false
    markers.setPressed(id)
    map.easeTo({
      center: [place.longitude, place.latitude],
      duration: getAnimationDuration(),
      offset: [-right / 2, -bottom / 2],
      zoom: Math.max(map.getZoom(), CLUSTER_MAX_ZOOM + 1),
    })

    return true
  }

  function requestSelectionClose() {
    if (!drawer.activeId && !tripPlayer.activeId && !isTripListUrl()) {
      return
    }

    if (getHistoryState()[HISTORY_STATE_KEY]) {
      history.back()
      return
    }

    history.replaceState(getHistoryState(), '', `${getMapPath()}${window.location.search}`)
    syncSelection()
  }

  function showFallback(message: string, fallbackOptions: { retry?: boolean } = {}) {
    report({ kind: 'map', message })
    loading.hidden = true
    fallbackMessage.textContent = message
    fallbackRetry.hidden = !fallbackOptions.retry
    fallback.hidden = false
    canvas.setAttribute('aria-hidden', 'true')
  }

  function hideFallback() {
    loading.hidden = true
    fallback.hidden = true
    canvas.removeAttribute('aria-hidden')
  }

  /** The places the map frames: those of the period picked, or every one if it has none. */
  function getFramedPlaces() {
    const shown = markers.getShownPlaces()
    return shown.length > 0 ? shown : places
  }

  function fitAll() {
    if (!map || places.length === 0) {
      return
    }

    const view = getAllPlacesView(getFramedPlaces(), isNarrow(), getFramingInset())

    map.fitBounds(view.bounds, { ...view.fitBoundsOptions, duration: getAnimationDuration() })
    framedOnAll = true
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
    // The map is framed on every place for the picture, then put back; this covers it.
    loading.textContent = messages.screenshotRendering
    loading.hidden = false

    try {
      const blob = await captureMap(map, {
        formatPlaceCount: (count) => t(messages, 'clusterPlaces', { count }),
        getPlaces: (current) => markers.getScreenshotPlaces(current),
        legend: elements.legend,
        places: getFramedPlaces(),
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

  for (const button of elements.fallbackPlaces) {
    button.addEventListener(
      'click',
      () => {
        const id = button.dataset.tripMapPlace

        if (id) {
          selectPlace(id, button)
        }
      },
      { signal },
    )
  }

  elements.drawerClose.addEventListener('click', requestSelectionClose, { signal })
  fallbackRetry.addEventListener('click', () => window.location.reload(), { signal })
  screenshotButton?.addEventListener('click', () => void takeScreenshot(), { signal })

  // The Moments the drawer and the list fetch link their trips, picked in the trip list,
  // and the drawer's link the other places they were at.
  page.addEventListener(
    'click',
    (event) => {
      const target = event.target instanceof Element ? event.target : null
      const trip = target?.closest<HTMLElement>('[data-trip-map-trip-pick]')
      const place = target?.closest<HTMLElement>('[data-trip-map-place-link]')
      const inSide = (element: Element) =>
        elements.drawer.contains(element) || Boolean(elements.moments?.contains(element))
      const tripId = trip?.dataset.tripMapTripPick

      if (trip && tripId && inSide(trip) && elements.tripList && tripsById.has(tripId)) {
        event.preventDefault()
        showTripInList(tripId)
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

  for (const link of elements.tripLinks) {
    const id = link.dataset.tripMapTripLink!

    // The trip list's rows are the list's own: picked first, then played.
    if (elements.tripList?.contains(link)) {
      continue
    }

    link.addEventListener(
      'focus',
      () => {
        tripOverview.highlight(id)
        tripOverview.reveal(id)
      },
      { signal },
    )
    link.addEventListener('blur', () => tripOverview.highlight(undefined), { signal })
    link.addEventListener(
      'click',
      (event) => {
        event.preventDefault()
        selectTrip(id)
      },
      { signal },
    )
  }

  // The period stays as picked going back and forward; the address follows it.
  window.addEventListener(
    'popstate',
    () => {
      syncSelection()
      syncPeriodUrl()
    },
    { signal },
  )
  // The map follows its container's size by itself. The open place is centred again
  // once a resize settles, and only when the width changed: a phone's address bar
  // showing and hiding only changes the height, and shouldn't keep moving the map.
  let resizeTimer: ReturnType<typeof setTimeout> | undefined
  let lastWidth = window.innerWidth

  window.addEventListener(
    'resize',
    () => {
      clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        const place = drawer.activeId ? markers.getPlace(drawer.activeId) : undefined

        if (window.innerWidth !== lastWidth && place) {
          moveMapToPlace(place)
        } else if (
          window.innerWidth !== lastWidth &&
          framedOnAll &&
          !drawer.activeId &&
          !tripPlayer.activeId
        ) {
          // A phone turned sideways, or a window narrowed: every place back in view.
          fitAll()
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
        (drawer.activeId || tripPlayer.activeId || tripList?.isOpen) &&
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
  darkMode.addEventListener(
    'change',
    () => {
      if (map && mapLoaded) {
        mapStyle = getMapStyle()
        map.setStyle(mapStyle)
      }
    },
    { signal },
  )

  upgradeHashUrl()
  syncSelection({ focus: true, moveMap: false })

  // What the page was rendered with open has been drawn by the next frame but one; only
  // what opens after that slides in (see styles.css).
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
  pickPeriod(getUrlPeriod())

  // A year in the legend, pressed, narrows the map to it; pressed again, every place
  // again, or the whole year from one of its months.
  for (const button of elements.yearButtons) {
    button.addEventListener(
      'click',
      () => {
        const year = Number(button.dataset.tripMapYear)
        const picked = markers.period

        pickPeriod(picked?.year === year && picked.month === undefined ? undefined : { year })
      },
      { signal },
    )
  }

  elements.yearClear?.addEventListener(
    'click',
    () => {
      pickPeriod(undefined)
      elements.yearButtons[0]?.focus()
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
      // Opens already framed on the places, rather than on the globe and then flying in.
      map = new mapboxgl.Map({
        // Beside the trip list, on its page.
        ...getAllPlacesView(getFramedPlaces(), isNarrow(), getFramingInset()),
        attributionControl: false,
        container: canvas,
        dragRotate: true,
        projection: 'globe',
        style: mapStyle,
        touchPitch: true,
      })

      // The attribution stays folded into its ⓘ button at every width, opening on a click.
      map.addControl(new mapboxgl.AttributionControl({ compact: true }))

      if (mapLanguage) {
        map.addControl(
          new MapboxLanguage({ defaultLanguage: mapLanguage }) as unknown as mapboxgl.IControl,
        )
      }

      map.on('style.load', () => {
        applyFog()
        // Under the routes and the player's layers, once the map has loaded.
        fogLayer.add()
        markers.addIndex()
        tripOverview.add()
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
          framedOnAll = false
          viewBeforeList = undefined
        }
      })
      map.on('idle', () => markers.sync())
      map.on('moveend', () => markers.sync())
      // A drag keeps the places coming in as it goes; a zoom waits for its end, when
      // the clusters have settled.
      let moveFrame: number | undefined
      map.on('move', () => {
        if (moveFrame !== undefined || map!.isZooming()) {
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

        if (screenshotButton) {
          screenshotButton.disabled = false
        }

        if (loadTimeout) {
          clearTimeout(loadTimeout)
          loadTimeout = undefined
        }

        hideFallback()

        // The theme changed while the map loaded.
        if (getMapStyle() !== mapStyle) {
          mapStyle = getMapStyle()
          map!.setStyle(mapStyle)
        }

        applyFog()
        fogLayer.add()
        markers.sync()

        const urlPlaceId = getUrlPlaceId()
        const urlPlace = urlPlaceId ? markers.getPlace(urlPlaceId) : undefined
        const pickedTripId = getUrlPickedTripId()

        if (getUrlTripId()) {
          syncTripFromUrl()
        } else if (pickedTripId) {
          // Picked before the map could fly to it.
          frameTripBesideList(pickedTripId)
        } else if (urlPlaceId && urlPlace) {
          drawer.open(urlPlace, { focus: false, trigger: markers.getButton(urlPlaceId) })
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

/** The basemap language for a locale: Chinese by script, anything else by its language. */
function getMapLanguage(locale: string) {
  const [language = 'en', ...rest] = locale.split('-')
  const tags = new Set(rest.map((tag) => tag.toLowerCase()))

  if (language.toLowerCase() === 'zh') {
    return tags.has('hant') || tags.has('tw') || tags.has('hk') || tags.has('mo')
      ? 'zh-Hant'
      : 'zh-Hans'
  }

  return language.toLowerCase()
}
