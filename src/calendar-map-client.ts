import MapboxLanguage from '@mapbox/mapbox-gl-language'
import mapboxgl, { type GeoJSONSource, type PaddingOptions } from 'mapbox-gl'
import { createTripPlayer } from './trip-player/player'
import { initializeLightboxMedia } from './moment-lightbox-client'

type MarkerData = {
  color: string
  count: number
  id: string
  latestYear: number
  latitude: number
  longitude: number
  name: string
}

type MarkerEntry = {
  button: HTMLButtonElement
  marker: mapboxgl.Marker
  popup: mapboxgl.Popup
}

type ClusterMarkerEntry = {
  marker: mapboxgl.Marker
  popup: mapboxgl.Popup
}

const HISTORY_STATE_KEY = 'calendarMapSelection'
const MAP_PATH = '/map/'
const TRIP_PATH_PATTERN = /^\/trip\/([^/]+)\/?$/
const LOCATION_INDEX_LAYER_ID = 'calendar-location-index'
const LOCATION_SOURCE_ID = 'calendar-locations'
const LOCATION_CLUSTER_MAX_ZOOM = 9
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
let destroyCurrentMap: (() => void) | undefined
let currentMapPage: HTMLElement | undefined

function initializeCalendarMap() {
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
  const fallback = queryRequired<HTMLElement>(page, '[data-calendar-map-fallback]')
  const fallbackMessage = queryRequired<HTMLElement>(page, '[data-calendar-map-fallback-message]')
  const loading = queryRequired<HTMLElement>(page, '[data-calendar-map-loading]')
  const lightbox = queryRequired<HTMLDialogElement>(page, '[data-calendar-map-lightbox]')
  const legend = queryRequired<HTMLElement>(page, '[data-calendar-map-legend]')
  const tripSelect = page.querySelector<HTMLSelectElement>('[data-calendar-map-trip-select]')

  const controller = new AbortController()
  const { signal } = controller
  const lightboxMedia = initializeLightboxMedia(lightbox, signal)
  const locations = parseMarkerData(dataElement)
  const locationsById = new Map(locations.map((location) => [location.id, location]))
  // Each place's drawer content, fetched from /map/place/<id>/ the first time it's needed.
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
  let map: mapboxgl.Map | undefined
  let mapLoaded = false
  let activeLocationId: string | undefined
  let activeTrigger: HTMLButtonElement | undefined
  let suppressMarkerFocusPopup = false
  let lightboxIndex = 0
  let lightboxItems: HTMLElement[] = []
  let loadTimeout: ReturnType<typeof setTimeout> | undefined

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
      drawerContent.replaceChildren(createLoadingMessage('没能加载这个地点的记录，请稍后再试。'))
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
    if (drawer.hidden) {
      return
    }

    if (lightbox.open) {
      lightbox.close()
    }

    drawer.hidden = true
    drawerContent.replaceChildren()
    activeLocationId = undefined

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

  function setMarkersHidden(hidden: boolean) {
    for (const marker of markers.values()) {
      marker.button.hidden = hidden
    }

    if (hidden) {
      clearClusterMarkers()
    } else {
      syncClusterMarkers()
    }
  }

  function syncTripFromUrl() {
    const id = getUrlTripId()

    if (tripSelect) {
      tripSelect.value = id ?? ''
    }

    document.title = (id && tripPlayer.getPageTitle(id)) || page!.dataset.mapTitle || document.title

    if (id) {
      if (!mapLoaded) {
        return
      }

      legend.hidden = true
      setMarkersHidden(true)
      tripPlayer.start(id)
      return
    }

    if (tripPlayer.activeId) {
      tripPlayer.stop()
      legend.hidden = false
      setMarkersHidden(false)
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

  function showFallback(message: string) {
    loading.hidden = true
    fallbackMessage.textContent = message
    fallback.hidden = false
    canvas.setAttribute('aria-hidden', 'true')
  }

  function hideFallback() {
    loading.hidden = true
    fallback.hidden = true
    canvas.removeAttribute('aria-hidden')
  }

  function fitAllLocations() {
    if (!map || locations.length === 0) {
      return
    }

    if (locations.length === 1) {
      const location = locations[0]!
      map.jumpTo({ center: [location.longitude, location.latitude], zoom: 4 })
      return
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const location of locations) {
      bounds.extend([location.longitude, location.latitude])
    }

    map.fitBounds(bounds, {
      duration: getAnimationDuration(),
      maxZoom: 4,
      padding: { bottom: 80, left: 56, right: 56, top: 88 },
    })
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
      const popup = new mapboxgl.Popup({
        closeButton: false,
        closeOnClick: false,
        focusAfterOpen: false,
        offset: 12,
      }).setText(`${location.name} · ${location.count} 条记录`)

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

  function createLocationIndex() {
    if (!map || map.getSource(LOCATION_SOURCE_ID)) {
      return
    }

    clearClusterMarkers()

    for (const marker of markers.values()) {
      marker.button.hidden = false
    }

    map.addSource(LOCATION_SOURCE_ID, {
      cluster: true,
      clusterMaxZoom: LOCATION_CLUSTER_MAX_ZOOM,
      clusterProperties: {
        momentCount: ['+', ['get', 'count']],
      },
      clusterRadius: 64,
      data: {
        features: locations.map((location) => ({
          geometry: {
            coordinates: [location.longitude, location.latitude],
            type: 'Point' as const,
          },
          properties: {
            count: location.count,
            id: location.id,
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

  function syncClusterMarkers() {
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
    const clusters = new Map<
      number,
      {
        coordinates: [number, number]
        locationCount: number
        momentCount: number
      }
    >()

    for (const feature of map.queryRenderedFeatures({ layers: [LOCATION_INDEX_LAYER_ID] })) {
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
            locationCount: Number(properties.point_count) || 0,
            momentCount: Number(properties.momentCount) || 0,
          })
        }
      } else if (typeof properties.id === 'string') {
        visibleLocationIds.add(properties.id)
      }
    }

    for (const [id, marker] of markers) {
      marker.button.hidden = !visibleLocationIds.has(id)
    }

    clearClusterMarkers()

    for (const [id, cluster] of clusters) {
      const button = document.createElement('button')
      const dot = document.createElement('span')
      const popup = new mapboxgl.Popup({
        closeButton: false,
        closeOnClick: false,
        focusAfterOpen: false,
        offset: 16,
      }).setText(`${cluster.locationCount} 个地点 · ${cluster.momentCount} 条记录`)

      button.type = 'button'
      button.className =
        'calendar-map-cluster group flex size-12 cursor-pointer items-center justify-center border-0 bg-transparent p-0 focus-visible:outline-none'
      button.setAttribute(
        'aria-label',
        `${cluster.locationCount} 个地点，共 ${cluster.momentCount} 条记录，点击放大`,
      )
      dot.className =
        'flex h-8 min-w-8 items-center justify-center rounded-full border-2 border-bg bg-tx px-2 font-mono text-xs font-semibold text-bg shadow-[0_0.25rem_1rem_rgb(0_0_0/0.28)] transition-transform duration-[160ms] group-hover:scale-110 group-focus-visible:scale-110 motion-reduce:transition-none'
      dot.setAttribute('aria-hidden', 'true')
      dot.textContent = String(cluster.momentCount)
      button.append(dot)

      const showPopup = () => popup.setLngLat(cluster.coordinates).addTo(map!)
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

          const source = map?.getSource(LOCATION_SOURCE_ID) as GeoJSONSource | undefined
          source?.getClusterExpansionZoom(id, (error, zoom) => {
            if (error || zoom == null || !map) {
              return
            }

            map.easeTo({
              center: cluster.coordinates,
              duration: getAnimationDuration(),
              zoom,
            })
          })
        },
        { signal },
      )

      const marker = new mapboxgl.Marker({ anchor: 'center', element: button })
        .setLngLat(cluster.coordinates)
        .addTo(map)
      button.setAttribute('role', 'button')

      clusterMarkers.set(id, { marker, popup })
    }
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
  // Pointing at or focusing the picker is a hint that another trip is next.
  for (const type of ['pointerenter', 'focus'] as const) {
    tripSelect?.addEventListener(type, tripPlayer.prefetch, { once: true, signal })
  }

  tripSelect?.addEventListener(
    'change',
    () => {
      if (tripSelect.value) {
        selectTrip(tripSelect.value)
      } else {
        requestSelectionClose()
      }
    },
    { signal },
  )
  window.addEventListener('popstate', () => syncLocationFromHash(), { signal })
  window.addEventListener(
    'resize',
    () => {
      map?.resize()

      if (activeLocationId) {
        const location = locationsById.get(activeLocationId)

        if (location) {
          moveMapToLocation(location)
        }
      }
    },
    { signal },
  )
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
        map.setStyle(getMapStyle())
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
      map = new mapboxgl.Map({
        attributionControl: true,
        center: [110, 32],
        container: canvas,
        dragRotate: true,
        projection: 'globe',
        style: getMapStyle(),
        touchPitch: true,
        zoom: 2,
      })

      map.addControl(
        new MapboxLanguage({ defaultLanguage: 'zh-Hans' }) as unknown as mapboxgl.IControl,
      )
      createMarkers()

      map.on('style.load', () => {
        applyFog()
        createLocationIndex()
        tripPlayer.restoreLayers()

        if (tripPlayer.activeId) {
          setMarkersHidden(true)
        }
      })
      map.on('click', () => {
        if (activeLocationId) {
          requestSelectionClose()
        }
      })
      map.on('idle', syncClusterMarkers)
      map.on('moveend', syncClusterMarkers)
      map.on('load', () => {
        mapLoaded = true

        if (loadTimeout) {
          clearTimeout(loadTimeout)
          loadTimeout = undefined
        }

        hideFallback()
        applyFog()
        syncClusterMarkers()

        const hashLocationId = getHashLocationId()

        if (getUrlTripId()) {
          syncTripFromUrl()
        } else if (hashLocationId) {
          activeTrigger = markers.get(hashLocationId)?.button ?? activeTrigger
          openLocation(hashLocationId, { focus: false })
        } else {
          fitAllLocations()
        }
      })
      map.on('error', () => {
        if (!mapLoaded) {
          showFallback('地图资源加载失败。')
        }
      })

      loadTimeout = setTimeout(() => {
        if (!mapLoaded) {
          showFallback('地图加载超时。')
        }
      }, 12_000)
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

document.addEventListener('astro:page-load', initializeCalendarMap)
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
