import MapboxLanguage from '@mapbox/mapbox-gl-language'
import mapboxgl, { type PaddingOptions } from 'mapbox-gl'
import { SITE_TITLE } from './consts'
import { createPopupContent, MAP_POPUP_CLASS, parseJson } from './trip-player/dom'
import { createTripOverview } from './trip-player/overview'
import { createTripPlayer } from './trip-player/player'
import type { TripSummary } from './trip-player/types'
import { captureMap, savePicture } from './map-screenshot'
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

type MarkerEntry = {
  button: HTMLButtonElement
  marker: mapboxgl.Marker
  popup: mapboxgl.Popup
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
// Below this zoom, each place is a small dot without its count, so the crowded parts
// of the map stay readable; the count is in its name on hover.
const FAR_ZOOM = 5.5
const MAP_PATH = '/map/'
const TRIP_PATH_PATTERN = /^\/trip\/([^/]+)\/?$/
// Opening a place zooms in at least this far, close enough to tell it from its neighbours.
const PLACE_ZOOM = 10
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
  const placeHtml = new Map<string, string>()
  const placeRequests = new Map<string, Promise<string | undefined>>()
  const markers = new Map<string, MarkerEntry>()
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
      zoom: Math.max(map.getZoom(), PLACE_ZOOM),
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
   * off centre once it's gone. Drops the padding without
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

  function setMarkersHidden(hidden: boolean) {
    for (const marker of markers.values()) {
      setMarkerShown(marker, !hidden)
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
      button.className =
        'calendar-map-marker group flex size-11 cursor-pointer items-center justify-center border-0 bg-transparent p-0 focus-visible:outline-none'
      button.setAttribute(
        'aria-label',
        `${location.name}，${location.count} 条记录，最近记录于 ${location.latestYear} 年`,
      )
      button.setAttribute('aria-pressed', 'false')
      button.style.setProperty('--marker-color', location.color)
      dot.className =
        'calendar-map-marker__dot flex h-[1.125rem] min-w-[1.125rem] items-center justify-center rounded-full border-2 border-white/95 bg-[var(--marker-color)] px-[0.1875rem] font-mono text-[0.625rem] font-semibold text-white shadow-[0_0.125rem_0.75rem_rgb(0_0_0/0.24),0_0_0_1px_rgb(0_0_0/0.12)] transition-[transform,box-shadow] duration-[160ms] ease-in-out group-hover:scale-125 group-hover:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] group-focus-visible:scale-125 group-focus-visible:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] group-aria-[pressed=true]:scale-125 group-aria-[pressed=true]:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] motion-reduce:transition-none'
      dot.setAttribute('aria-hidden', 'true')
      dot.textContent = String(location.count)
      button.append(dot)

      const showPopup = () => {
        if (suppressMarkerFocusPopup) {
          return
        }

        popup.setLngLat([location.longitude, location.latitude]).addTo(map!)
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

      const marker = new mapboxgl.Marker({ anchor: 'center', element: button })
        .setLngLat([location.longitude, location.latitude])
        .addTo(map)
      button.setAttribute('role', 'button')

      markers.set(location.id, { button, marker, popup })
    }
  }

  /** A place's, cluster's or city's name on hover, styled like a route's. */
  function createPlacePopup(offset: number, meta: string, title: string) {
    return new mapboxgl.Popup({
      className: MAP_POPUP_CLASS,
      closeButton: false,
      closeOnClick: false,
      focusAfterOpen: false,
      offset,
    }).setDOMContent(createPopupContent({ meta, title }))
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
      // Marks a far-out view, where the dots shrink (see index.css).
      const markFar = () => page.toggleAttribute('data-map-far', map!.getZoom() < FAR_ZOOM)
      markFar()
      map.on('zoom', markFar)
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
