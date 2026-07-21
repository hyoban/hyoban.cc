import MapboxLanguage from '@mapbox/mapbox-gl-language'
import mapboxgl, { type GeoJSONSource, type PaddingOptions } from 'mapbox-gl'

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
  const lightboxImage = queryRequired<HTMLImageElement>(page, '[data-map-lightbox-image]')

  const controller = new AbortController()
  const { signal } = controller
  const locations = parseMarkerData(dataElement)
  const locationsById = new Map(locations.map(location => [location.id, location]))
  const templates = new Map<string, HTMLTemplateElement>()
  const markers = new Map<string, MarkerEntry>()
  const clusterMarkers = new Map<number, ClusterMarkerEntry>()
  const darkMode = window.matchMedia('(prefers-color-scheme: dark)')
  let map: mapboxgl.Map | undefined
  let mapLoaded = false
  let activeLocationId: string | undefined
  let activeTrigger: HTMLButtonElement | undefined
  let suppressMarkerFocusPopup = false
  let lightboxIndex = 0
  let lightboxItems: HTMLElement[] = []
  let loadTimeout: ReturnType<typeof setTimeout> | undefined

  for (const template of page.querySelectorAll<HTMLTemplateElement>('[data-calendar-map-template]')) {
    const id = template.dataset.calendarMapTemplate

    if (id) {
      templates.set(id, template)
    }
  }

  function getMapStyle() {
    return `mapbox://styles/mapbox/${darkMode.matches ? 'dark' : 'light'}-v11`
  }

  function getHashLocationId() {
    if (!window.location.hash) {
      return undefined
    }

    try {
      const id = decodeURIComponent(window.location.hash.slice(1))
      return locationsById.has(id) ? id : undefined
    } catch {
      return undefined
    }
  }

  function getHistoryState() {
    return history.state && typeof history.state === 'object'
      ? history.state as Record<string, unknown>
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

  function openLocation(
    id: string,
    options: { focus?: boolean, moveMap?: boolean } = {},
  ) {
    const location = locationsById.get(id)
    const template = templates.get(id)

    if (!location || !template) {
      return
    }

    if (lightbox.open) {
      lightbox.close()
    }

    activeLocationId = id
    drawerTitle.textContent = location.name
    drawerContent.replaceChildren(template.content.cloneNode(true))
    drawer.hidden = false

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

  function syncLocationFromHash(options: { focus?: boolean, moveMap?: boolean } = {}) {
    const id = getHashLocationId()

    if (id) {
      const markerButton = markers.get(id)?.button
      const fallbackButton = page!.querySelector<HTMLButtonElement>(`[data-map-location-id="${CSS.escape(id)}"]`)
      activeTrigger = markerButton ?? fallbackButton ?? activeTrigger
      openLocation(id, options)
      return
    }

    closeDrawer({
      restoreFocus: options.focus !== false,
    })
  }

  function selectLocation(id: string, trigger: HTMLButtonElement) {
    const currentId = getHashLocationId()
    const currentState = getHistoryState()
    const nextState = {
      ...currentState,
      [HISTORY_STATE_KEY]: currentId
        ? currentState[HISTORY_STATE_KEY] === true
        : true,
    }
    const url = `${window.location.pathname}${window.location.search}#${encodeURIComponent(id)}`

    activeTrigger = trigger

    if (currentId) {
      history.replaceState(nextState, '', url)
    } else {
      history.pushState(nextState, '', url)
    }

    openLocation(id)
  }

  function requestDrawerClose() {
    if (!activeLocationId) {
      return
    }

    if (getHistoryState()[HISTORY_STATE_KEY]) {
      history.back()
      return
    }

    history.replaceState(
      getHistoryState(),
      '',
      `${window.location.pathname}${window.location.search}`,
    )
    closeDrawer()
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
      button.className = 'calendar-map-marker group flex size-11 cursor-pointer items-center justify-center border-0 bg-transparent p-0 focus-visible:outline-none'
      button.setAttribute('aria-label', `${location.name}，${location.count} 条记录，最近记录于 ${location.latestYear} 年`)
      button.setAttribute('aria-pressed', 'false')
      button.style.setProperty('--marker-color', location.color)
      dot.className = 'calendar-map-marker__dot flex h-[1.125rem] min-w-[1.125rem] items-center justify-center rounded-full border-2 border-white/95 bg-[var(--marker-color)] px-[0.1875rem] font-mono text-[0.625rem] font-semibold text-white shadow-[0_0.125rem_0.75rem_rgb(0_0_0/0.24),0_0_0_1px_rgb(0_0_0/0.12)] transition-[transform,box-shadow] duration-[160ms] ease-in-out group-hover:scale-125 group-hover:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] group-focus-visible:scale-125 group-focus-visible:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] group-aria-[pressed=true]:scale-125 group-aria-[pressed=true]:shadow-[0_0.25rem_1rem_rgb(0_0_0/0.3),0_0_0_2px_var(--color-tx)] motion-reduce:transition-none'
      dot.setAttribute('aria-hidden', 'true')
      dot.textContent = String(location.count)
      button.append(dot)

      const showPopup = () => {
        if (suppressMarkerFocusPopup) {
          return
        }

        popup
          .setLngLat([location.longitude, location.latitude])
          .addTo(map!)
      }
      const hidePopup = () => popup.remove()

      button.addEventListener('mouseenter', showPopup, { signal })
      button.addEventListener('mouseleave', hidePopup, { signal })
      button.addEventListener('focus', showPopup, { signal })
      button.addEventListener('blur', hidePopup, { signal })
      button.addEventListener('click', (event) => {
        event.stopPropagation()
        hidePopup()
        selectLocation(location.id, button)
      }, { signal })

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
        features: locations.map(location => ({
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
    if (!map || !mapLoaded || !map.getSource(LOCATION_SOURCE_ID) || !map.isSourceLoaded(LOCATION_SOURCE_ID)) {
      return
    }

    const visibleLocationIds = new Set<string>()
    const clusters = new Map<number, {
      coordinates: [number, number]
      locationCount: number
      momentCount: number
    }>()

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
      button.className = 'calendar-map-cluster group flex size-12 cursor-pointer items-center justify-center border-0 bg-transparent p-0 focus-visible:outline-none'
      button.setAttribute('aria-label', `${cluster.locationCount} 个地点，共 ${cluster.momentCount} 条记录，点击放大`)
      dot.className = 'flex h-8 min-w-8 items-center justify-center rounded-full border-2 border-bg bg-tx px-2 font-mono text-xs font-semibold text-bg shadow-[0_0.25rem_1rem_rgb(0_0_0/0.28)] transition-transform duration-[160ms] group-hover:scale-110 group-focus-visible:scale-110 motion-reduce:transition-none'
      dot.setAttribute('aria-hidden', 'true')
      dot.textContent = String(cluster.momentCount)
      button.append(dot)

      const showPopup = () => popup.setLngLat(cluster.coordinates).addTo(map!)
      const hidePopup = () => popup.remove()

      button.addEventListener('mouseenter', showPopup, { signal })
      button.addEventListener('mouseleave', hidePopup, { signal })
      button.addEventListener('focus', showPopup, { signal })
      button.addEventListener('blur', hidePopup, { signal })
      button.addEventListener('click', (event) => {
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
      }, { signal })

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

    lightboxImage.src = item.dataset.lightboxSrc ?? ''
    lightboxImage.alt = item.dataset.lightboxAlt ?? ''

    if (!lightbox.open) {
      lightbox.showModal()
    }
  }

  for (const fallbackButton of page.querySelectorAll<HTMLButtonElement>('[data-map-location-id]')) {
    fallbackButton.addEventListener('click', () => {
      const id = fallbackButton.dataset.mapLocationId

      if (id) {
        selectLocation(id, fallbackButton)
      }
    }, { signal })
  }

  drawerClose.addEventListener('click', requestDrawerClose, { signal })
  window.addEventListener('popstate', () => syncLocationFromHash(), { signal })
  window.addEventListener('resize', () => {
    map?.resize()

    if (activeLocationId) {
      const location = locationsById.get(activeLocationId)

      if (location) {
        moveMapToLocation(location)
      }
    }
  }, { signal })
  window.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && activeLocationId && !lightbox.open) {
      requestDrawerClose()
    }
  }, { signal })
  darkMode.addEventListener('change', () => {
    if (map && mapLoaded) {
      map.setStyle(getMapStyle())
    }
  }, { signal })

  page.addEventListener('click', (event) => {
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
  }, { signal })

  lightbox.querySelector('[data-map-lightbox-close]')?.addEventListener('click', () => lightbox.close(), { signal })
  lightbox.querySelector('[data-map-lightbox-previous]')?.addEventListener('click', () => showLightboxItem(lightboxIndex - 1), { signal })
  lightbox.querySelector('[data-map-lightbox-next]')?.addEventListener('click', () => showLightboxItem(lightboxIndex + 1), { signal })
  lightbox.addEventListener('click', (event) => {
    if (event.target === lightbox) {
      lightbox.close()
    }
  }, { signal })
  lightbox.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowLeft') {
      showLightboxItem(lightboxIndex - 1)
    } else if (event.key === 'ArrowRight') {
      showLightboxItem(lightboxIndex + 1)
    }
  }, { signal })

  syncLocationFromHash({ focus: true, moveMap: false })

  const token = page.dataset.mapboxToken?.trim()

  if (!token) {
    showFallback('尚未配置 Mapbox Token，仍可通过地点列表浏览记录。')
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

      map.addControl(new MapboxLanguage({ defaultLanguage: 'zh-Hans' }) as unknown as mapboxgl.IControl)
      createMarkers()

      map.on('style.load', () => {
        applyFog()
        createLocationIndex()
      })
      map.on('click', requestDrawerClose)
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

        if (hashLocationId) {
          activeTrigger = markers.get(hashLocationId)?.button ?? activeTrigger
          openLocation(hashLocationId, { focus: false })
        } else {
          fitAllLocations()
        }
      })
      map.on('error', () => {
        if (!mapLoaded) {
          showFallback('地图资源加载失败，仍可通过地点列表浏览记录。')
        }
      })

      loadTimeout = setTimeout(() => {
        if (!mapLoaded) {
          showFallback('地图加载超时，仍可通过地点列表浏览记录。')
        }
      }, 12_000)
    } catch {
      map?.remove()
      canvas.replaceChildren()
      showFallback('浏览器无法初始化地图，仍可通过地点列表浏览记录。')
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
    return Array.isArray(value) ? value as MarkerData[] : []
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
  const { from, to } = event as Event & { from: URL, to: URL }

  if (from.pathname === to.pathname && from.search === to.search) {
    return
  }

  destroyCurrentMap?.()
})
