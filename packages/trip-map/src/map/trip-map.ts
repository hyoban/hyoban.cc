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
import { CLUSTER_MAX_ZOOM, createPlaceMarkers, getAllPlacesView } from './place-markers.ts'
import { captureMap, savePicture } from './screenshot.ts'
import { defaultUrls, getHistoryState, HISTORY_STATE_KEY, parseHash } from './selection-url.ts'

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
  /** The document title while a trip plays; without it the title is left alone. */
  getDocumentTitle?: ((trip: { id: string; title: string }) => string) | undefined
  /** A place's drawer content, an HTML fragment. Default: `/map/place/<id>/`. */
  getPlaceUrl?: ((id: string) => string) | undefined
  /** A trip's playback data, `TripData` as JSON. Default: `/trip/<id>/data.json`. */
  getTripDataUrl?: ((id: string) => string) | undefined
  /** The trip a pathname opens, if any. Default: the id in `/trip/<id>/`. */
  getTripId?: ((pathname: string) => string | undefined) | undefined
  /** A trip's page. Default: `/trip/<id>/`. */
  getTripPath?: ((id: string) => string) | undefined
  /**
   * The language the basemap's names are shown in, as @mapbox/mapbox-gl-language takes
   * it (`en`, `ja`, `zh-Hans`…); `false` leaves the basemap as it is. Default: from
   * the messages' locale.
   */
  mapLanguage?: string | false | undefined
  /** The map's own page, which closing a trip goes back to. Default: `/map/`. */
  mapPath?: string | undefined
  /** A Mapbox style URL, or one for each colour scheme. Default: Mapbox's light and dark. */
  mapStyle?: string | Readonly<{ dark: string; light: string }> | undefined
  /** Otherwise read from the page's `data-trip-map-token`. */
  mapboxToken?: string | undefined
  /** Words to replace the Chinese defaults with. */
  messages?: Partial<Messages> | undefined
  /** The tracks a video may be set to. */
  music?: readonly MusicTrack[] | undefined
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
  const urls = {
    getPlaceUrl: options.getPlaceUrl ?? defaultUrls.getPlaceUrl,
    getTripDataUrl: options.getTripDataUrl ?? defaultUrls.getTripDataUrl,
    getTripId: options.getTripId ?? defaultUrls.getTripId,
    getTripPath: options.getTripPath ?? defaultUrls.getTripPath,
    mapPath: options.mapPath ?? defaultUrls.mapPath,
  }
  const elements = collectMapElements(page)
  const { canvas, fallback, fallbackMessage, fallbackRetry, loading, screenshotButton } = elements
  const controller = new AbortController()
  const { signal } = controller
  const reducedMotion = getReducedMotionQuery()
  const darkMode = window.matchMedia('(prefers-color-scheme: dark)')
  const places = options.places ?? parseJson<PlaceMarker[]>(page, '[data-trip-map-places]', [])
  const trips = options.trips ?? parseJson<TripSummary[]>(page, '[data-trip-map-trips]', [])
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
    inlineTrip,
    messages,
    music: options.music,
    onClose: requestSelectionClose,
    page,
    panel: elements.panel,
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

      if (moveMap) {
        moveMapToPlace(place)
      }
    },
    onRestoreFocus: (button) => markers.focusQuietly(button),
    reducedMotion,
    signal,
  })

  const fogLayer = createFogLayer({
    areaElement: elements.fogArea,
    button: elements.fogButton,
    getMap: () => (mapLoaded ? map : undefined),
    getTheme: () => readTheme(page),
    isDark: () => darkMode.matches,
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

  function getHashPlaceId() {
    const id = parseHash(window.location.hash)
    return id && markers.has(id) ? id : undefined
  }

  function getUrlTripId() {
    const id = urls.getTripId(window.location.pathname)
    return id && tripPlayer.has(id) ? id : undefined
  }

  function getDrawerPadding(): PaddingOptions {
    if (isNarrow()) {
      return {
        bottom: Math.min(elements.drawer.offsetHeight + 32, window.innerHeight * 0.82),
        left: 32,
        right: 32,
        top: 80,
      }
    }

    return { bottom: 32, left: 32, right: elements.drawer.offsetWidth + 32, top: 80 }
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
      padding: getDrawerPadding(),
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

  function syncTripFromUrl() {
    const id = getUrlTripId()
    const title = id ? tripPlayer.getTitle(id) : undefined

    if (options.getDocumentTitle) {
      document.title =
        id && title !== undefined ? options.getDocumentTitle({ id, title }) : documentTitle
    }

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
      fitAll()
    }
  }

  function syncSelection(selectOptions: { focus?: boolean; moveMap?: boolean } = {}) {
    syncTripFromUrl()

    if (tripPlayer.activeId) {
      drawer.close({ restoreFocus: false })
      return
    }

    const id = getHashPlaceId()
    const place = id ? markers.getPlace(id) : undefined

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

    drawer.close({ restoreFocus: selectOptions.focus !== false })
  }

  function setSelectionUrl(url: string) {
    const hasSelection = Boolean(getHashPlaceId() ?? getUrlTripId())
    const currentState = getHistoryState()
    const nextState = {
      ...currentState,
      [HISTORY_STATE_KEY]: hasSelection ? currentState[HISTORY_STATE_KEY] === true : true,
    }

    if (hasSelection) {
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
    setSelectionUrl(
      `${window.location.pathname}${window.location.search}#${encodeURIComponent(id)}`,
    )
    drawer.open(place, { trigger })
  }

  function selectTrip(id: string) {
    setSelectionUrl(urls.getTripPath(id))
    syncSelection({ focus: false })
  }

  function requestSelectionClose() {
    if (!drawer.activeId && !tripPlayer.activeId) {
      return
    }

    if (getHistoryState()[HISTORY_STATE_KEY]) {
      history.back()
      return
    }

    history.replaceState(
      getHistoryState(),
      '',
      getUrlTripId() ? urls.mapPath : `${window.location.pathname}${window.location.search}`,
    )
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

  function fitAll() {
    if (!map || places.length === 0) {
      return
    }

    const view = getAllPlacesView(places, isNarrow())

    if ('bounds' in view) {
      map.fitBounds(view.bounds, { ...view.fitBoundsOptions, duration: getAnimationDuration() })
    } else {
      map.jumpTo(view)
    }

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
        places,
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

  for (const link of elements.tripLinks) {
    const id = link.dataset.tripMapTripLink!

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

  window.addEventListener('popstate', () => syncSelection(), { signal })
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
        (drawer.activeId || tripPlayer.activeId) &&
        !drawer.lightboxOpen
      ) {
        requestSelectionClose()
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

  syncSelection({ focus: true, moveMap: false })

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
        ...getAllPlacesView(places, isNarrow()),
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

        const hashPlaceId = getHashPlaceId()
        const hashPlace = hashPlaceId ? markers.getPlace(hashPlaceId) : undefined

        if (getUrlTripId()) {
          syncTripFromUrl()
        } else if (hashPlaceId && hashPlace) {
          drawer.open(hashPlace, { focus: false, trigger: markers.getButton(hashPlaceId) })
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
