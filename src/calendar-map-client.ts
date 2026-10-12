import MapboxLanguage from '@mapbox/mapbox-gl-language'
import mapboxgl, { type GeoJSONSource, type PaddingOptions } from 'mapbox-gl'
import { SITE_TITLE } from './consts'
import { createPopupContent, MAP_POPUP_CLASS, parseJson } from './trip-player/dom'
import { createTripOverview } from './trip-player/overview'
import { createTripPlayer } from './trip-player/player'
import type { TripSummary } from './trip-player/types'
import { captureMap, savePicture, type ScreenshotPlace } from './map-screenshot'
import { initializeLightboxMedia } from './moment-lightbox-client'

type MarkerData = {
  color: string
  count: number
  id: string
  latestYear: number
  latitude: number
  longitude: number
  name: string
  years: number[]
}

/** How far a dot is pushed on screen, in pixels, to clear the dots beside it. */
type Nudge = { x: number; y: number }

type MarkerEntry = {
  button: HTMLButtonElement
  coordinates: [number, number]
  dot: HTMLElement
  marker: mapboxgl.Marker
  nudge: Nudge
  popup: mapboxgl.Popup
  radius: number
}

type ClusterInfo = {
  coordinates: [number, number]
  latestYear: number
  locationCount: number
  momentCount: number
}

type ClusterMarkerEntry = {
  button: HTMLButtonElement
  coordinates: [number, number]
  dot: HTMLElement
  marker: mapboxgl.Marker
  /** Busiest first, once they've been looked up. */
  members?: MarkerData[]
  nudge: Nudge
  popup: mapboxgl.Popup
  radius: number
}

type MapStats = {
  drivingKm: number
  firstYear: number
  lastYear: number
  moments: number
  places: number
  trips: number
}

const HISTORY_STATE_KEY = 'calendarMapSelection'
const MAP_PATH = '/map/'
const TRIP_PATH_PATTERN = /^\/trip\/([^/]+)\/?$/
const LOCATION_INDEX_LAYER_ID = 'calendar-location-index'
const LOCATION_SOURCE_ID = 'calendar-locations'
const LOCATION_CLUSTER_MAX_ZOOM = 9
// How close on screen, in pixels, places are gathered into one cluster, and how many
// it takes: two places side by side aren't a crowd, so they stay as they are, even
// if one covers part of the other.
const LOCATION_CLUSTER_RADIUS = 20
const LOCATION_CLUSTER_MIN_POINTS = 3
// The dots' radii on screen, in pixels (a place with one Moment, one with more, and a
// cluster), and the gap kept between dots pushed apart so none covers another.
const DOT_RADIUS = { cluster: 12, place: 9, single: 7 }
const DOT_GAP = 2
const DOT_SPREAD_ROUNDS = 8
// Opening a cluster frames its places, no closer than this.
const CLUSTER_OPEN_MAX_ZOOM = 11
const CLUSTER_OPEN_PADDING = 80
// The markers are set in reading order for the keyboard, in rows this tall.
const MARKER_ROW_HEIGHT = 48
// How long the markers take to fade as a trip opens (see index.css), before they're
// taken off the map.
const MARKER_FADE = 200
// The drawer sliding away as it closes; it slides in from the same spot (see index.css).
const DRAWER_EXIT = { duration: 200, easing: 'cubic-bezier(0.4, 0, 1, 1)' }
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
let destroyCurrentMap: (() => void) | undefined
let currentMapPage: HTMLElement | undefined

export function initializeCalendarMap() {
  const page = document.querySelector<HTMLElement>('[data-calendar-map]')

  if (page && page === currentMapPage && destroyCurrentMap) {
    return
  }

  destroyCurrentMap?.()
  destroyCurrentMap = undefined
  currentMapPage = undefined

  if (!page) {
    return
  }

  currentMapPage = page

  const canvas = queryRequired<HTMLElement>(page, '[data-calendar-map-canvas]')
  const dataElement = queryRequired<HTMLScriptElement>(page, '[data-calendar-map-locations]')
  const drawer = queryRequired<HTMLElement>(page, '[data-calendar-map-drawer]')
  const drawerClose = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-drawer-close]')
  const drawerContent = queryRequired<HTMLElement>(page, '[data-calendar-map-drawer-content]')
  const drawerTitle = queryRequired<HTMLElement>(page, '[data-calendar-map-drawer-title]')
  const drawerEyebrow = queryRequired<HTMLElement>(page, '[data-calendar-map-drawer-eyebrow]')
  const fallback = queryRequired<HTMLElement>(page, '[data-calendar-map-fallback]')
  const fallbackMessage = queryRequired<HTMLElement>(page, '[data-calendar-map-fallback-message]')
  const fallbackRetry = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-fallback-retry]')
  const loading = queryRequired<HTMLElement>(page, '[data-calendar-map-loading]')
  const lightbox = queryRequired<HTMLDialogElement>(page, '[data-calendar-map-lightbox]')
  const tripLinks = [...page.querySelectorAll<HTMLAnchorElement>('[data-calendar-map-trip-link]')]
  const screenshotButton = page.querySelector<HTMLButtonElement>('[data-calendar-map-screenshot]')
  const legend = page.querySelector<HTMLElement>('[data-calendar-map-legend]')

  const controller = new AbortController()
  const { signal } = controller
  const lightboxMedia = initializeLightboxMedia(lightbox, signal)
  const locations = parseMarkerData(dataElement)
  const locationsById = new Map(locations.map((location) => [location.id, location]))
  // Each place's drawer content, fetched from /map/place/<id>/ the first time it's needed.
  // A cluster takes the colour of its latest year, like a place does.
  const yearColors = new Map(locations.map((location) => [location.latestYear, location.color]))
  const placeHtml = new Map<string, string>()
  const placeRequests = new Map<string, Promise<string | undefined>>()
  const markers = new Map<string, MarkerEntry>()
  const clusterMarkers = new Map<number, ClusterMarkerEntry>()
  const darkMode = window.matchMedia('(prefers-color-scheme: dark)')
  const tripPlayer = createTripPlayer({
    getMap: () => (mapLoaded ? map : undefined),
    onClose: requestSelectionClose,
    page,
    signal,
  })
  const tripOverview = createTripOverview({
    getInk: () => getComputedStyle(page).getPropertyValue('--color-tx').trim() || '#21201c',
    getMap: () => map,
    // Pointing at a route, or focusing its link, starts loading that trip.
    onHighlight: (id) => tripPlayer.prefetch(id),
    onSelect: selectTrip,
    reducedMotion,
    signal,
    trips: parseJson<TripSummary[]>(page, '[data-calendar-map-trips]', []),
  })
  let map: mapboxgl.Map | undefined
  let mapLoaded = false
  let markerTimer: ReturnType<typeof setTimeout> | undefined
  let drawerExit: Animation | undefined
  let activeLocationId: string | undefined
  let activeTrigger: HTMLButtonElement | undefined
  let suppressMarkerFocusPopup = false
  let lightboxIndex = 0
  let lightboxItems: HTMLElement[] = []
  let loadTimeout: ReturnType<typeof setTimeout> | undefined
  // The style the map was last given, to catch a theme change made while it loaded.
  let mapStyle = getMapStyle()
  // Whether the view is still the one framing every place, untouched since: only then
  // does a change of width frame them again.
  let framedOnAll = true

  function getMapStyle() {
    return `mapbox://styles/mapbox/${darkMode.matches ? 'dark' : 'light'}-v11`
  }

  function getHashValue() {
    if (!window.location.hash) {
      return undefined
    }

    try {
      return decodeURIComponent(window.location.hash.slice(1))
    } catch {
      return undefined
    }
  }

  function getHashLocationId() {
    const id = getHashValue()
    return id && locationsById.has(id) ? id : undefined
  }

  function getUrlTripId() {
    const match = window.location.pathname.match(TRIP_PATH_PATTERN)
    let id: string | undefined

    try {
      id = match ? decodeURIComponent(match[1]!) : undefined
    } catch {
      id = undefined
    }

    return id && tripPlayer.has(id) ? id : undefined
  }

  function getTripPath(id: string) {
    return `/trip/${encodeURIComponent(id)}/`
  }

  function getHistoryState() {
    return history.state && typeof history.state === 'object'
      ? (history.state as Record<string, unknown>)
      : {}
  }

  function getDrawerPadding(): PaddingOptions {
    if (window.matchMedia('(max-width: 48rem)').matches) {
      return {
        bottom: Math.min(drawer.offsetHeight + 32, window.innerHeight * 0.82),
        left: 32,
        right: 32,
        top: 80,
      }
    }

    return {
      bottom: 32,
      left: 32,
      right: drawer.offsetWidth + 32,
      top: 80,
    }
  }

  function getAnimationDuration() {
    return reducedMotion.matches ? 0 : 500
  }

  function moveMapToLocation(location: MarkerData) {
    if (!map || !mapLoaded) {
      return
    }

    framedOnAll = false
    map.easeTo({
      center: [location.longitude, location.latitude],
      duration: getAnimationDuration(),
      padding: getDrawerPadding(),
      zoom: Math.max(map.getZoom(), LOCATION_CLUSTER_MAX_ZOOM + 1),
    })
  }

  /** Fetches a place's drawer content once; a failed fetch is tried again next time. */
  function loadPlace(id: string) {
    let request = placeRequests.get(id)

    if (!request) {
      request = fetch(`/map/place/${encodeURIComponent(id)}/`, { signal })
        .then((response) => (response.ok ? response.text() : undefined))
        .catch(() => undefined)
        .then((html) => {
          if (html === undefined) {
            placeRequests.delete(id)
          } else {
            placeHtml.set(id, html)
          }

          return html
        })
      placeRequests.set(id, request)
    }

    return request
  }

  function renderPlace(html: string | undefined) {
    const template = document.createElement('template')

    if (html === undefined) {
      drawerContent.replaceChildren(createLoadingMessage('没能加载这个地点的记录。'))
    } else {
      template.innerHTML = html
      drawerContent.replaceChildren(template.content)
    }

    drawerContent.removeAttribute('aria-busy')
  }

  function openLocation(id: string, options: { focus?: boolean; moveMap?: boolean } = {}) {
    const location = locationsById.get(id)

    if (!location) {
      return
    }

    if (lightbox.open) {
      lightbox.close()
    }

    activeLocationId = id
    drawerTitle.textContent = location.name
    drawerEyebrow.textContent = `${location.count} 条记录 · ${location.years.map((year) => `${year} 年`).join('、')}`
    drawerExit?.cancel()
    drawerExit = undefined
    drawer.hidden = false

    if (placeHtml.has(id)) {
      renderPlace(placeHtml.get(id))
    } else {
      drawerContent.setAttribute('aria-busy', 'true')
      drawerContent.replaceChildren(createLoadingMessage('加载中…'))
      void loadPlace(id).then((html) => {
        if (activeLocationId === id) {
          renderPlace(html)
        }
      })
    }

    for (const [markerId, marker] of markers) {
      marker.button.setAttribute('aria-pressed', String(markerId === id))
    }

    if (options.moveMap !== false) {
      moveMapToLocation(location)
    }

    if (options.focus !== false) {
      drawerClose.focus({ preventScroll: true })
    }
  }

  function closeDrawer(options: { restoreFocus?: boolean } = {}) {
    if (drawer.hidden || drawerExit) {
      return
    }

    if (lightbox.open) {
      lightbox.close()
    }

    activeLocationId = undefined

    // Slides away first, then hides and lets go of its content; a place opened
    // meanwhile cancels the slide and keeps the drawer. A script animation, as going
    // back to /map/ has the router put the map back into the page, which would end a
    // CSS transition on the spot.
    const exit = drawer.animate(
      [
        {},
        {
          opacity: 0,
          translate: window.matchMedia('(max-width: 48rem)').matches ? '0 2rem' : '1.5rem 0',
        },
      ],
      { ...DRAWER_EXIT, duration: reducedMotion.matches ? 0 : DRAWER_EXIT.duration },
    )

    drawerExit = exit
    exit.finished.then(
      () => {
        if (drawerExit === exit) {
          drawerExit = undefined
          drawer.hidden = true
          drawerContent.replaceChildren()
          exit.cancel()
        }
      },
      () => {},
    )
    clearDrawerPadding()

    for (const marker of markers.values()) {
      marker.button.setAttribute('aria-pressed', 'false')
    }

    if (options.restoreFocus !== false && activeTrigger?.isConnected && !activeTrigger.hidden) {
      suppressMarkerFocusPopup = true

      try {
        activeTrigger.focus({ preventScroll: true })
      } finally {
        suppressMarkerFocusPopup = false
      }
    }
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
   * Takes a hidden marker off the map rather than only hiding it: Mapbox repositions
   * every marker on the map each frame it draws, which adds up during trip playback.
   */
  function setMarkerShown(entry: MarkerEntry, shown: boolean) {
    entry.button.hidden = !shown

    if (!map || shown === Boolean(entry.marker.getElement().isConnected)) {
      return
    }

    if (shown) {
      entry.marker.addTo(map)
    } else {
      entry.popup.remove()
      entry.marker.remove()
    }
  }

  /** Where a dot sits once pushed aside, for its name to hang over. */
  function getNudgedLngLat(coordinates: [number, number], nudge: Nudge) {
    const point = map!.project(coordinates)
    point.x += nudge.x
    point.y += nudge.y
    return map!.unproject(point)
  }

  function setNudge(entry: MarkerEntry | ClusterMarkerEntry, x: number, y: number) {
    if (entry.nudge.x === x && entry.nudge.y === y) {
      return
    }

    entry.nudge = { x, y }
    entry.marker.setOffset([x, y])

    if (entry.popup.isOpen()) {
      entry.popup.setLngLat(getNudgedLngLat(entry.coordinates, entry.nudge))
    }
  }

  /**
   * Pushes dots that would cover one another apart on screen, each a little, until
   * every one shows: two places side by side stay two dots, and a place beside a
   * cluster keeps clear of it.
   */
  function spreadDots() {
    const dots = [...markers.values(), ...clusterMarkers.values()]
      .filter((entry) => !entry.button.hidden && entry.button.isConnected)
      .map((entry) => {
        const point = map!.project(entry.coordinates)

        return {
          entry,
          // A cluster's number widens its dot.
          radius: Math.max(entry.radius, entry.dot.offsetWidth / 2),
          x: point.x,
          x0: point.x,
          y: point.y,
          y0: point.y,
        }
      })

    for (let round = 0; round < DOT_SPREAD_ROUNDS; round++) {
      let moved = false

      for (let i = 0; i < dots.length; i++) {
        for (let j = i + 1; j < dots.length; j++) {
          const first = dots[i]!
          const second = dots[j]!
          const minimum = first.radius + second.radius + DOT_GAP
          let dx = second.x - first.x
          let dy = second.y - first.y
          let distance = Math.hypot(dx, dy)

          if (distance >= minimum) {
            continue
          }

          // Two dots on the very same spot part sideways.
          if (distance === 0) {
            dx = 1
            dy = 0
            distance = 1
          }

          const push = (minimum - distance) / 2
          first.x -= (dx / distance) * push
          first.y -= (dy / distance) * push
          second.x += (dx / distance) * push
          second.y += (dy / distance) * push
          moved = true
        }
      }

      if (!moved) {
        break
      }
    }

    for (const dot of dots) {
      setNudge(dot.entry, Math.round(dot.x - dot.x0), Math.round(dot.y - dot.y0))
    }
  }

  /**
   * Sets the markers in reading order, row by row from the top, so the keyboard walks
   * them as the eye does rather than in the order they came into view. Left alone
   * while the keyboard is on one of them.
   */
  function orderMarkers() {
    const container = map!.getCanvasContainer()
    const entries = [...markers.values(), ...clusterMarkers.values()].filter(
      (entry) => !entry.button.hidden && entry.button.isConnected,
    )

    if (entries.some((entry) => entry.button === document.activeElement)) {
      return
    }

    const ordered = entries
      .map((entry) => {
        const point = map!.project(entry.coordinates)
        return { button: entry.button, x: point.x + entry.nudge.x, y: point.y + entry.nudge.y }
      })
      .sort(
        (first, second) =>
          Math.floor(first.y / MARKER_ROW_HEIGHT) - Math.floor(second.y / MARKER_ROW_HEIGHT) ||
          first.x - second.x,
      )
      .map((item) => item.button)
    const current = [
      ...container.querySelectorAll(
        ':scope > .calendar-map-marker, :scope > .calendar-map-cluster',
      ),
    ]

    if (
      ordered.length === current.length &&
      ordered.every((button, index) => current[index] === button)
    ) {
      return
    }

    for (const button of ordered) {
      container.append(button)
    }
  }

  function setMarkersHidden(hidden: boolean) {
    for (const marker of markers.values()) {
      setMarkerShown(marker, !hidden)
    }

    if (hidden) {
      clearClusterMarkers()
    } else {
      syncClusterMarkers()
    }
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

    if (on) {
      page!.dataset.mapTrip = ''
      markerTimer = setTimeout(
        () => {
          markerTimer = undefined
          setMarkersHidden(true)
        },
        reducedMotion.matches ? 0 : MARKER_FADE,
      )
      return
    }

    // Back on the map still faded, then let go of the attribute so they fade in.
    setMarkersHidden(false)
    requestAnimationFrame(() => {
      if (!tripPlayer.activeId) {
        delete page!.dataset.mapTrip
      }
    })
  }

  function syncTripFromUrl() {
    const id = getUrlTripId()

    document.title = (id && tripPlayer.getPageTitle(id)) || page!.dataset.mapTitle || document.title

    if (id) {
      // Set before the map loads too, so its routes are added hidden.
      tripOverview.hide(id)
      page!.dataset.mapTrip = ''

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
    if (tripPlayer.activeId || page!.dataset.mapTrip !== undefined) {
      tripPlayer.stop()
      tripOverview.show()
      setTripMode(false)
      fitAllLocations()
    }
  }

  function syncLocationFromHash(options: { focus?: boolean; moveMap?: boolean } = {}) {
    syncTripFromUrl()

    if (tripPlayer.activeId) {
      closeDrawer({ restoreFocus: false })
      return
    }

    const id = getHashLocationId()

    if (id) {
      const markerButton = markers.get(id)?.button
      const fallbackButton = page!.querySelector<HTMLButtonElement>(
        `[data-map-location-id="${CSS.escape(id)}"]`,
      )
      activeTrigger = markerButton ?? fallbackButton ?? activeTrigger
      openLocation(id, options)
      return
    }

    closeDrawer({
      restoreFocus: options.focus !== false,
    })
  }

  function setSelectionUrl(url: string) {
    const hasSelection = Boolean(getHashLocationId() ?? getUrlTripId())
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

  function selectLocation(id: string, trigger: HTMLButtonElement) {
    activeTrigger = trigger
    setSelectionUrl(
      `${window.location.pathname}${window.location.search}#${encodeURIComponent(id)}`,
    )
    openLocation(id)
  }

  function selectTrip(id: string) {
    setSelectionUrl(getTripPath(id))
    syncLocationFromHash({ focus: false })
  }

  function requestSelectionClose() {
    if (!activeLocationId && !tripPlayer.activeId) {
      return
    }

    if (getHistoryState()[HISTORY_STATE_KEY]) {
      history.back()
      return
    }

    history.replaceState(
      getHistoryState(),
      '',
      getUrlTripId() ? MAP_PATH : `${window.location.pathname}${window.location.search}`,
    )
    syncLocationFromHash()
  }

  function showFallback(message: string, options: { retry?: boolean } = {}) {
    loading.hidden = true
    fallbackMessage.textContent = message
    fallbackRetry.hidden = !options.retry
    fallback.hidden = false
    canvas.setAttribute('aria-hidden', 'true')
  }

  function hideFallback() {
    loading.hidden = true
    fallback.hidden = true
    canvas.removeAttribute('aria-hidden')
  }

  /** The view that shows every place, leaving room for the toolbar and the legend. */
  function getAllLocationsView() {
    if (locations.length === 1) {
      const location = locations[0]!
      return { center: [location.longitude, location.latitude] as [number, number], zoom: 4 }
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const location of locations) {
      bounds.extend([location.longitude, location.latitude])
    }

    // A phone held upright is short of width, so the sides get only a little room.
    const side = window.matchMedia('(max-width: 48rem)').matches ? 20 : 56

    return {
      bounds,
      fitBoundsOptions: {
        maxZoom: 4,
        padding: { bottom: 80, left: side, right: side, top: 88 },
      },
    }
  }

  function fitAllLocations() {
    if (!map || locations.length === 0) {
      return
    }

    const view = getAllLocationsView()

    if ('bounds' in view) {
      map.fitBounds(view.bounds, { ...view.fitBoundsOptions, duration: getAnimationDuration() })
    } else {
      map.jumpTo(view)
    }

    framedOnAll = true
  }

  function applyFog() {
    if (!map) {
      return
    }

    map.setFog({
      color: 'rgba(0, 0, 0, 0)',
      'high-color': 'rgba(255, 255, 255, 0.08)',
      'horizon-blend': 0.08,
      'space-color': 'rgba(0, 0, 0, 0)',
    })
  }

  function createMarkers() {
    if (!map) {
      return
    }

    for (const location of locations) {
      const button = document.createElement('button')
      const dot = document.createElement('span')
      const popup = createPlacePopup(12, `${location.count} 条记录`, location.name)

      button.type = 'button'
      // The button is no bigger than its dot and a thin ring around it: a wider one
      // would cover the dots beside it. (Mapbox sets the marker's pointer-events
      // itself, so the button can't hand them to the dot.)
      button.className =
        'calendar-map-marker group flex cursor-pointer items-center justify-center border-0 bg-transparent p-0 focus-visible:outline-none before:absolute before:-inset-1 before:rounded-full before:content-[""]'
      button.setAttribute(
        'aria-label',
        `${location.name}，${location.count} 条记录，最近记录于 ${location.latestYear} 年`,
      )
      button.setAttribute('aria-pressed', 'false')
      button.style.setProperty('--marker-color', location.color)
      // A single Moment is a smaller dot, without a number.
      dot.className =
        (location.count > 1 ? 'h-[1.125rem] min-w-[1.125rem] ' : 'h-3.5 min-w-3.5 ') +
        'calendar-map-marker__dot flex items-center justify-center rounded-full border-2 border-white/95 bg-[var(--marker-color)] px-[0.1875rem] font-mono text-[0.625rem] font-semibold text-white shadow-[0_0.125rem_0.75rem_rgb(0_0_0/0.24),0_0_0_1px_rgb(0_0_0/0.12)] transition-[transform,box-shadow] duration-[160ms] ease-in-out group-hover:scale-125 group-hover:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] group-focus-visible:scale-125 group-focus-visible:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] group-aria-[pressed=true]:scale-125 group-aria-[pressed=true]:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] motion-reduce:transition-none'
      dot.setAttribute('aria-hidden', 'true')
      dot.textContent = location.count > 1 ? String(location.count) : ''
      button.append(dot)

      const entry: MarkerEntry = {
        button,
        coordinates: [location.longitude, location.latitude],
        dot,
        marker: new mapboxgl.Marker({ anchor: 'center', element: button }).setLngLat([
          location.longitude,
          location.latitude,
        ]),
        nudge: { x: 0, y: 0 },
        popup,
        radius: location.count > 1 ? DOT_RADIUS.place : DOT_RADIUS.single,
      }

      const showPopup = () => {
        if (suppressMarkerFocusPopup) {
          return
        }

        popup.setLngLat(getNudgedLngLat(entry.coordinates, entry.nudge)).addTo(map!)
      }
      const hidePopup = () => popup.remove()

      const prefetch = () => void loadPlace(location.id)

      button.addEventListener('mouseenter', showPopup, { signal })
      button.addEventListener('mouseleave', hidePopup, { signal })
      button.addEventListener('focus', showPopup, { signal })
      button.addEventListener('blur', hidePopup, { signal })
      // Pointing at a place is a good hint it'll be opened, so its Moments start loading.
      button.addEventListener('pointerenter', prefetch, { signal })
      button.addEventListener('focus', prefetch, { signal })
      button.addEventListener(
        'click',
        (event) => {
          event.stopPropagation()
          hidePopup()
          selectLocation(location.id, button)
        },
        { signal },
      )

      // Put on the map by the first sync, if it's not in a cluster there.
      button.setAttribute('role', 'button')
      markers.set(location.id, entry)
    }
  }

  /** A place's or cluster's name on hover, styled like a route's. */
  function createPlacePopup(offset: number, meta: string, title: string) {
    return new mapboxgl.Popup({
      className: MAP_POPUP_CLASS,
      closeButton: false,
      closeOnClick: false,
      focusAfterOpen: false,
      offset,
    }).setDOMContent(createPopupContent({ meta, title }))
  }

  function createLocationIndex() {
    if (!map || map.getSource(LOCATION_SOURCE_ID)) {
      return
    }

    // A new source numbers its clusters afresh; the next sync draws them.
    clearClusterMarkers()

    map.addSource(LOCATION_SOURCE_ID, {
      cluster: true,
      clusterMaxZoom: LOCATION_CLUSTER_MAX_ZOOM,
      clusterMinPoints: LOCATION_CLUSTER_MIN_POINTS,
      clusterProperties: {
        latestYear: ['max', ['get', 'latestYear']],
        momentCount: ['+', ['get', 'count']],
      },
      clusterRadius: LOCATION_CLUSTER_RADIUS,
      data: {
        features: locations.map((location) => ({
          geometry: {
            coordinates: [location.longitude, location.latitude],
            type: 'Point' as const,
          },
          properties: {
            count: location.count,
            id: location.id,
            latestYear: location.latestYear,
          },
          type: 'Feature' as const,
        })),
        type: 'FeatureCollection',
      },
      type: 'geojson',
    })
    map.addLayer({
      id: LOCATION_INDEX_LAYER_ID,
      paint: {
        'circle-opacity': 0,
        'circle-radius': 1,
      },
      source: LOCATION_SOURCE_ID,
      type: 'circle',
    })
  }

  function clearClusterMarkers() {
    for (const cluster of clusterMarkers.values()) {
      cluster.popup.remove()
      cluster.marker.remove()
    }

    clusterMarkers.clear()
  }

  /**
   * Puts the places and clusters the map gathers at this zoom on it, and takes off
   * the rest. `settled` is false while a drag is under way, when only what's in view
   * needs keeping up.
   */
  function syncClusterMarkers(options: { settled?: boolean } = {}) {
    if (
      tripPlayer.activeId ||
      !map ||
      !mapLoaded ||
      !map.getSource(LOCATION_SOURCE_ID) ||
      !map.isSourceLoaded(LOCATION_SOURCE_ID)
    ) {
      return
    }

    const visibleLocationIds = new Set<string>()
    const clusters = new Map<number, ClusterInfo>()

    // The source's loaded tiles reach past the view, so the places about to come in
    // are on the map before a drag brings them in.
    for (const feature of map.querySourceFeatures(LOCATION_SOURCE_ID)) {
      if (feature.geometry.type !== 'Point') {
        continue
      }

      const properties = feature.properties ?? {}

      if (properties.cluster) {
        const id = Number(properties.cluster_id)
        const coordinates = feature.geometry.coordinates

        if (Number.isFinite(id) && coordinates.length >= 2) {
          clusters.set(id, {
            coordinates: [coordinates[0]!, coordinates[1]!],
            latestYear: Number(properties.latestYear) || 0,
            locationCount: Number(properties.point_count) || 0,
            momentCount: Number(properties.momentCount) || 0,
          })
        }
      } else if (typeof properties.id === 'string') {
        visibleLocationIds.add(properties.id)
      }
    }

    for (const [id, marker] of markers) {
      setMarkerShown(marker, visibleLocationIds.has(id))
    }

    // A cluster's id stands for the same places at the same spot, so the ones still
    // shown are kept: a focused cluster keeps its focus, and a hovered one its name.
    for (const [id, cluster] of clusterMarkers) {
      if (!clusters.has(id)) {
        cluster.popup.remove()
        cluster.marker.remove()
        clusterMarkers.delete(id)
      }
    }

    for (const [id, cluster] of clusters) {
      if (!clusterMarkers.has(id)) {
        clusterMarkers.set(id, createClusterMarker(map, id, cluster))
      }
    }

    spreadDots()

    if (options.settled !== false) {
      orderMarkers()
    }
  }

  /** A cluster's places, busiest first. */
  function getClusterMembers(map: mapboxgl.Map, id: number) {
    const source = map.getSource(LOCATION_SOURCE_ID) as GeoJSONSource | undefined

    if (!source) {
      return Promise.resolve<MarkerData[]>([])
    }

    return new Promise<MarkerData[]>((resolve) =>
      source.getClusterLeaves(id, Infinity, 0, (error, features) =>
        resolve(
          error
            ? []
            : (features ?? [])
                .map((leaf) => locationsById.get(String(leaf.properties?.id)))
                .filter((place) => place !== undefined)
                .sort(
                  (first, second) =>
                    second.count - first.count || first.name.length - second.name.length,
                ),
        ),
      ),
    )
  }

  /** A cluster goes by its busiest place: 上海等. */
  function getClusterName(members: MarkerData[]) {
    return `${members[0]!.name}等`
  }

  /**
   * Frames a cluster's places close enough to tell apart, in one move; a cluster
   * whose places can't be looked up opens one step at a time instead.
   */
  async function openCluster(map: mapboxgl.Map, id: number, entry: ClusterMarkerEntry) {
    const members = entry.members ?? (await getClusterMembers(map, id))

    framedOnAll = false

    if (members.length === 0) {
      const source = map.getSource(LOCATION_SOURCE_ID) as GeoJSONSource | undefined
      source?.getClusterExpansionZoom(id, (error, zoom) => {
        if (!error && zoom != null) {
          map.easeTo({ center: entry.coordinates, duration: getAnimationDuration(), zoom })
        }
      })
      return
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const member of members) {
      bounds.extend([member.longitude, member.latitude])
    }

    map.fitBounds(bounds, {
      duration: getAnimationDuration(),
      maxZoom: CLUSTER_OPEN_MAX_ZOOM,
      padding: CLUSTER_OPEN_PADDING,
    })
  }

  function createClusterMarker(
    map: mapboxgl.Map,
    id: number,
    cluster: ClusterInfo,
  ): ClusterMarkerEntry {
    const button = document.createElement('button')
    const dot = document.createElement('span')
    const meta = `${cluster.momentCount} 条记录`
    const popup = createPlacePopup(16, meta, `${cluster.locationCount} 个地点`)

    button.type = 'button'
    // No bigger than its dot and a thin ring, as a place's.
    button.className =
      'calendar-map-cluster group flex cursor-pointer items-center justify-center border-0 bg-transparent p-0 focus-visible:outline-none before:absolute before:-inset-1 before:rounded-full before:content-[""]'
    button.setAttribute(
      'aria-label',
      `${cluster.locationCount} 个地点，共 ${cluster.momentCount} 条记录`,
    )
    button.style.setProperty(
      '--marker-color',
      yearColors.get(cluster.latestYear) ?? 'var(--color-tx)',
    )
    dot.className =
      'flex h-6 min-w-6 items-center justify-center rounded-full border-2 border-white/95 bg-[var(--marker-color)] px-1.5 font-mono text-[0.6875rem] font-semibold text-white shadow-[0_0.25rem_1rem_rgb(0_0_0/0.28),0_0_0_1px_rgb(0_0_0/0.12)] transition-transform duration-[160ms] group-hover:scale-110 group-focus-visible:scale-110 motion-reduce:transition-none'
    dot.setAttribute('aria-hidden', 'true')
    dot.textContent = String(cluster.momentCount)
    button.append(dot)

    const entry: ClusterMarkerEntry = {
      button,
      coordinates: cluster.coordinates,
      dot,
      marker: new mapboxgl.Marker({ anchor: 'center', element: button })
        .setLngLat(cluster.coordinates)
        .addTo(map),
      nudge: { x: 0, y: 0 },
      popup,
      radius: DOT_RADIUS.cluster,
    }
    button.setAttribute('role', 'button')

    const showPopup = () =>
      popup.setLngLat(getNudgedLngLat(entry.coordinates, entry.nudge)).addTo(map)
    const hidePopup = () => popup.remove()

    button.addEventListener('mouseenter', showPopup, { signal })
    button.addEventListener('mouseleave', hidePopup, { signal })
    button.addEventListener('focus', showPopup, { signal })
    button.addEventListener('blur', hidePopup, { signal })
    button.addEventListener(
      'click',
      (event) => {
        event.stopPropagation()
        hidePopup()
        void openCluster(map, id, entry)
      },
      { signal },
    )

    // Named after its busiest place once its places are known, on the card and to
    // the screen reader, so it tells where it is without opening it.
    void getClusterMembers(map, id).then((members) => {
      if (clusterMarkers.get(id) !== entry || members.length === 0) {
        return
      }

      const title = `${getClusterName(members)} ${cluster.locationCount} 个地点`

      entry.members = members
      popup.setDOMContent(createPopupContent({ meta, title }))
      button.setAttribute('aria-label', `${title}，共 ${cluster.momentCount} 条记录`)
    })

    return entry
  }

  /**
   * The places a screenshot draws, gathered as the map gathers them at its framing:
   * each cluster named after its busiest place, with every place in it.
   */
  async function getScreenshotPlaces(map: mapboxgl.Map): Promise<ScreenshotPlace[]> {
    const places: ScreenshotPlace[] = []
    const seen = new Set<string>()

    for (const feature of map.queryRenderedFeatures({ layers: [LOCATION_INDEX_LAYER_ID] })) {
      const properties = feature.properties ?? {}

      if (feature.geometry.type !== 'Point') {
        continue
      }

      if (!properties.cluster) {
        const place = locationsById.get(String(properties.id))

        if (place && !seen.has(place.id)) {
          seen.add(place.id)
          places.push(place)
        }

        continue
      }

      const id = Number(properties.cluster_id)

      if (seen.has(`cluster-${id}`)) {
        continue
      }

      seen.add(`cluster-${id}`)

      const members = await getClusterMembers(map, id)
      const [longitude, latitude] = feature.geometry.coordinates
      const latestYear = Number(properties.latestYear) || 0

      places.push({
        color: yearColors.get(latestYear) ?? members[0]?.color ?? '#888',
        count: Number(properties.momentCount) || 0,
        latestYear,
        latitude: latitude!,
        longitude: longitude!,
        members,
        name: members.length > 0 ? getClusterName(members) : `${properties.point_count} 个地点`,
      })
    }

    return places
  }

  function showLightboxItem(index: number) {
    if (lightboxItems.length === 0) {
      return
    }

    lightboxIndex = (index + lightboxItems.length) % lightboxItems.length
    const item = lightboxItems[lightboxIndex]

    if (!item) {
      return
    }

    lightboxMedia.show(item)

    if (!lightbox.open) {
      lightbox.showModal()
    }
  }

  for (const fallbackButton of page.querySelectorAll<HTMLButtonElement>('[data-map-location-id]')) {
    fallbackButton.addEventListener(
      'click',
      () => {
        const id = fallbackButton.dataset.mapLocationId

        if (id) {
          selectLocation(id, fallbackButton)
        }
      },
      { signal },
    )
  }

  drawerClose.addEventListener('click', requestSelectionClose, { signal })
  fallbackRetry.addEventListener('click', () => window.location.reload(), { signal })

  screenshotButton?.addEventListener('click', () => void takeScreenshot(), { signal })

  /**
   * Saves a picture of the map framed on every place, named and counted, with the
   * totals above it, then puts the view back as it was.
   */
  async function takeScreenshot() {
    if (!map || !mapLoaded || !screenshotButton || screenshotButton.ariaBusy === 'true') {
      return
    }

    const pageStyle = getComputedStyle(page!)
    const stats = parseJson<MapStats | undefined>(page!, '[data-calendar-map-stats]', undefined)
    const number = (value: number) => value.toLocaleString('zh-CN')

    screenshotButton.ariaBusy = 'true'
    // The map is framed on every place for the picture, then put back; this covers it.
    loading.textContent = '正在生成截图…'
    loading.hidden = false

    try {
      const blob = await captureMap(map, {
        getPlaces: getScreenshotPlaces,
        legend,
        places: locations,
        site: `${window.location.host}/map/`,
        stats: stats
          ? [
              stats.firstYear === stats.lastYear
                ? String(stats.firstYear)
                : `${stats.firstYear}–${stats.lastYear}`,
              `${number(stats.places)} 个地点`,
              `${number(stats.moments)} 条记录`,
              ...(stats.trips > 0 ? [`${stats.trips} 段旅程`] : []),
              ...(stats.drivingKm > 0 ? [`自驾 ${number(stats.drivingKm)} 公里`] : []),
            ]
          : [],
        theme: {
          background: pageStyle.backgroundColor,
          muted: pageStyle.getPropertyValue('--color-tx-3').trim() || '#82817b',
          text: pageStyle.getPropertyValue('--color-tx').trim() || '#21201c',
        },
        title: `${SITE_TITLE} 的足迹`,
      })
      const day = new Date().toLocaleDateString('en-CA').replaceAll('-', '')

      loading.hidden = true
      await savePicture(blob, `map-${day}.png`)
    } finally {
      loading.hidden = true
      screenshotButton.ariaBusy = null
    }
  }

  for (const link of tripLinks) {
    const id = link.dataset.calendarMapTripLink!

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
  window.addEventListener('popstate', () => syncLocationFromHash(), { signal })
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
        const location = activeLocationId ? locationsById.get(activeLocationId) : undefined

        if (window.innerWidth !== lastWidth && location) {
          moveMapToLocation(location)
        } else if (
          window.innerWidth !== lastWidth &&
          framedOnAll &&
          !activeLocationId &&
          !tripPlayer.activeId
        ) {
          // A phone turned sideways, or a window narrowed: every place back in view.
          fitAllLocations()
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
        (activeLocationId || tripPlayer.activeId) &&
        !lightbox.open
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

  page.addEventListener(
    'click',
    (event) => {
      const target = event.target

      if (!(target instanceof Element)) {
        return
      }

      const item = target.closest<HTMLElement>('[data-lightbox-item]')

      if (!item || !drawer.contains(item)) {
        return
      }

      lightboxItems = [...drawer.querySelectorAll<HTMLElement>('[data-lightbox-item]')]
      showLightboxItem(lightboxItems.indexOf(item))
    },
    { signal },
  )

  lightbox
    .querySelector('[data-map-lightbox-close]')
    ?.addEventListener('click', () => lightbox.close(), { signal })
  lightbox
    .querySelector('[data-map-lightbox-previous]')
    ?.addEventListener('click', () => showLightboxItem(lightboxIndex - 1), { signal })
  lightbox
    .querySelector('[data-map-lightbox-next]')
    ?.addEventListener('click', () => showLightboxItem(lightboxIndex + 1), { signal })
  lightbox.addEventListener(
    'click',
    (event) => {
      if (event.target === lightbox) {
        lightbox.close()
      }
    },
    { signal },
  )
  lightbox.addEventListener(
    'keydown',
    (event) => {
      if (event.key === 'ArrowLeft') {
        showLightboxItem(lightboxIndex - 1)
      } else if (event.key === 'ArrowRight') {
        showLightboxItem(lightboxIndex + 1)
      }
    },
    { signal },
  )

  syncLocationFromHash({ focus: true, moveMap: false })

  const token = page.dataset.mapboxToken?.trim()

  if (!token) {
    showFallback('尚未配置 Mapbox Token。')
  } else if (locations.length === 0) {
    showFallback('还没有带地点的 Moment。')
  } else {
    try {
      mapboxgl.accessToken = token
      // Opens already framed on the places, rather than on the globe and then flying in.
      map = new mapboxgl.Map({
        ...getAllLocationsView(),
        attributionControl: false,
        container: canvas,
        dragRotate: true,
        projection: 'globe',
        style: mapStyle,
        touchPitch: true,
      })

      // The attribution stays folded into its ⓘ button at every width, opening on a click.
      map.addControl(new mapboxgl.AttributionControl({ compact: true }))
      map.addControl(
        new MapboxLanguage({ defaultLanguage: 'zh-Hans' }) as unknown as mapboxgl.IControl,
      )
      createMarkers()

      map.on('style.load', () => {
        applyFog()
        createLocationIndex()
        tripOverview.add()
        tripPlayer.restoreLayers()

        if (tripPlayer.activeId) {
          setMarkersHidden(true)
        }
      })
      map.on('click', (event) => {
        if (tripOverview.click(event.point, event.lngLat)) {
          return
        }

        if (activeLocationId) {
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
      map.on('idle', () => syncClusterMarkers())
      map.on('moveend', () => syncClusterMarkers())
      // A drag keeps the places coming in as it goes; a zoom waits for its end, when
      // the clusters have settled.
      let moveFrame: number | undefined
      map.on('move', () => {
        if (moveFrame !== undefined || map!.isZooming()) {
          return
        }

        moveFrame = requestAnimationFrame(() => {
          moveFrame = undefined
          syncClusterMarkers({ settled: false })
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
        syncClusterMarkers()

        const hashLocationId = getHashLocationId()

        if (getUrlTripId()) {
          syncTripFromUrl()
        } else if (hashLocationId) {
          activeTrigger = markers.get(hashLocationId)?.button ?? activeTrigger
          openLocation(hashLocationId, { focus: false })
        }
      })
      map.on('error', () => {
        if (!mapLoaded) {
          showFallback('地图资源加载失败。', { retry: true })
        }
      })

      // A slow connection gets a word that the map is still coming, and only much
      // later the list of places with a way to try again.
      loadTimeout = setTimeout(() => {
        if (!mapLoaded) {
          loading.textContent = '地图加载得有点慢，再等等…'
          loadTimeout = setTimeout(() => {
            if (!mapLoaded) {
              showFallback('地图加载超时。', { retry: true })
            }
          }, 30_000)
        }
      }, 10_000)
    } catch {
      map?.remove()
      canvas.replaceChildren()
      showFallback('浏览器无法初始化地图。')
    }
  }

  destroyCurrentMap = () => {
    controller.abort()

    if (loadTimeout) {
      clearTimeout(loadTimeout)
    }

    if (markerTimer) {
      clearTimeout(markerTimer)
    }

    drawerExit?.cancel()

    for (const marker of markers.values()) {
      marker.popup.remove()
      marker.marker.remove()
    }

    clearClusterMarkers()

    map?.remove()
    canvas.replaceChildren()

    if (currentMapPage === page) {
      currentMapPage = undefined
    }
  }
}

function parseMarkerData(element: HTMLScriptElement): MarkerData[] {
  try {
    const value: unknown = JSON.parse(element.textContent ?? '[]')
    return Array.isArray(value) ? (value as MarkerData[]) : []
  } catch {
    return []
  }
}

function queryRequired<T extends Element>(root: ParentNode, selector: string) {
  const element = root.querySelector<T>(selector)

  if (!element) {
    throw new Error(`Missing required calendar map element: ${selector}`)
  }

  return element
}

document.addEventListener('astro:before-swap', (event) => {
  const { from, to } = event as Event & { from: URL; to: URL }

  if (from.pathname === to.pathname && from.search === to.search) {
    return
  }

  destroyCurrentMap?.()
})

function createLoadingMessage(text: string) {
  const message = document.createElement('p')
  message.className = 'pt-4 text-sm text-tx-3'
  message.textContent = text
  return message
}
