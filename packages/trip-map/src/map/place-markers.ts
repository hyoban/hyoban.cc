import mapboxgl from 'mapbox-gl'
import Supercluster, { type PointFeature } from 'supercluster'
import { getIconBody, getPlaceKindIcon } from '../icons.ts'
import { t, type Messages } from '../messages.ts'
import { createPopupContent, MAP_POPUP_CLASS } from '../player/dom.ts'
import type { PlaceMarker } from '../player/types.ts'
import { isInPeriod, type Period } from './period.ts'
import { getFramingPitch, getMapPitch } from './basemap.ts'
import type { ScreenshotPlace } from './screenshot.ts'
import {
  ALL_PLACES_MAX_ZOOM,
  CLUSTER_MAX_ZOOM,
  getAllPlacesPadding,
  type FramingInset,
} from '../static-map.ts'

// The places on the map, drawn as the Mac app's MapKit draws them: the newest photo taken
// at a place, framed in white with its year's dot at the corner, or for a place without
// one a balloon in its year's colour with its kind's icon and its name under it. They
// gather into stacks where they crowd at the zoom shown. The clusters are worked out
// here rather than by a clustered source, whose tiles mid-zoom mix two zooms' worth of
// them: here they're always the zoom's own, so they part and join as a zoom goes. Every
// pin stays on its very spot: one with no room there steps down, as MapKit's do, from its
// photo or named balloon to its kind's balloon alone, and then to its year's dot. The
// pins are buttons placed over the map, so they can be hovered, focused and pressed like
// any other.

export type { PlaceMarker }
export { CLUSTER_MAX_ZOOM }

/**
 * How much of a place's pin there's room for: its photo, or its balloon and name; its
 * kind's balloon alone; or its year's dot.
 */
type PinLevel = 'full' | 'icon' | 'dot'

/** The levels, most room first. */
const PIN_LEVELS: readonly PinLevel[] = ['full', 'icon', 'dot']

/** Where a pin is on screen: around its place, or a balloon's tail on it. */
type PinShape = {
  /** How far above its place the pin's middle is, in pixels. */
  lift: number
  radius: number
}

type MarkerEntry = {
  button: HTMLButtonElement
  coordinates: [number, number]
  count: number
  hasPhoto: boolean
  level: PinLevel
  marker: mapboxgl.Marker
  /** How wide its name is under a balloon, measured once. */
  nameWidth: number
  popup: mapboxgl.Popup
}

type ClusterInfo = {
  coordinates: [number, number]
  firstYear: number
  locationCount: number
  momentCount: number
}

type ClusterMarkerEntry = PinShape & {
  button: HTMLButtonElement
  coordinates: [number, number]
  marker: mapboxgl.Marker
  /** Busiest first. */
  members: PlaceMarker[]
  popup: mapboxgl.Popup
}

type Box = { bottom: number; left: number; right: number; top: number }

// How close on screen, in pixels of a 512-pixel tile as Mapbox draws them, places are
// gathered into one cluster, and how many it takes: two places side by side aren't a
// crowd, so they stay as they are.
const CLUSTER_RADIUS = 32
const CLUSTER_MIN_POINTS = 3
// The pins' sizes on screen, in pixels, as styles.css draws them: a photo's side, a
// balloon's circle and its tail, a stack's photo, and a dot.
const PIN = { balloon: 28, cluster: 38, dot: 10, photo: 34, tail: 6 }
const PHOTO_SHAPE: PinShape = { lift: 0, radius: PIN.photo / 2 }
const BALLOON_SHAPE: PinShape = { lift: PIN.tail + PIN.balloon / 2, radius: PIN.balloon / 2 }
const DOT_SHAPE: PinShape = { lift: 0, radius: PIN.dot / 2 }
const CLUSTER_SHAPE: PinShape = { lift: 0, radius: PIN.cluster / 2 }
// How far a stack's cards reach past its photo, as styles.css draws them.
const STACK_CARDS = 3
// How far two pins may reach into each other and both keep their room, in pixels: they
// may touch, as MapKit's do, but not cover one another.
const PIN_TOUCH = 2
// A balloon's name: how tall a line, its type as styles.css sets it, and the gap above it.
const LABEL_HEIGHT = 14
const LABEL_FONT = '600 11px system-ui, sans-serif'
const LABEL_GAP = 2
// Opening a cluster frames its places, no closer than this.
const CLUSTER_OPEN_MAX_ZOOM = 11
const CLUSTER_OPEN_PADDING = 80
// The map's width up to which it's a phone's, as the page's styles have it (48rem).
const NARROW_WIDTH = 768
// The markers are set in reading order for the keyboard, in rows this tall.
const MARKER_ROW_HEIGHT = 48

type PlaceProperties = { count: number; firstYear: number; id: string }
type ClusterProperties = { firstYear: number; momentCount: number }

export type PlaceMarkers = ReturnType<typeof createPlaceMarkers>

export function createPlaceMarkers({
  getAnimationDuration,
  getClearPadding,
  getMap,
  isPaused,
  messages,
  onPrefetch,
  onSelect,
  places,
  signal,
}: {
  getAnimationDuration: () => number
  /** Room kept on the map for what's open on its side, and around the rest. */
  getClearPadding: () => Required<mapboxgl.PaddingOptions>
  getMap: () => mapboxgl.Map | undefined
  /** Until the map has loaded, and while a trip plays, the markers stay off the map. */
  isPaused: () => boolean
  messages: Messages
  /** Pointing at a place is a good hint it'll be opened. */
  onPrefetch: (id: string) => void
  onSelect: (id: string, button: HTMLButtonElement) => void
  places: readonly PlaceMarker[]
  signal: AbortSignal
}) {
  const placesById = new Map(places.map((place) => [place.id, place]))
  // A cluster takes the colour of its latest year, like a place does.
  const yearColors = new Map(places.map((place) => [place.firstYear, place.color]))
  const markers = new Map<string, MarkerEntry>()
  const clusterMarkers = new Map<number, ClusterMarkerEntry>()
  // The places outside the period picked, left on the map as gray dots near the view.
  const dimDots = new Map<string, mapboxgl.Marker>()
  // The year or month picked: only the places with Moments then are on the map.
  let period: Period | undefined
  // The places of the trip whose sidebar is open: the only ones left on the map then.
  let tripPlaces: ReadonlySet<string> | undefined
  // Focus put back on a marker as the drawer closes shouldn't pop its name up.
  let quietFocus = false
  // The shown places, gathered at each zoom.
  let index = createIndex()

  /**
   * Takes a hidden marker off the map rather than only hiding it: Mapbox repositions
   * every marker on the map each frame it draws, which adds up during trip playback.
   */
  function setMarkerShown(entry: MarkerEntry, shown: boolean) {
    const map = getMap()

    // Synced every frame of a move: only what changes is touched.
    if (entry.button.hidden === shown) {
      entry.button.hidden = !shown
    }

    if (!map || shown === Boolean(entry.marker.getElement().isConnected)) {
      return
    }

    if (shown) {
      placeOnMap(map, entry)
    } else {
      entry.popup.remove()
      entry.marker.remove()
    }
  }

  /**
   * Adds a pin to the map already where it belongs: Mapbox moves it there only on the
   * map's next frame, which during a quick drag leaves a new one in the map's corner, or
   * one shown again where it last was, for a moment.
   */
  function placeOnMap(map: mapboxgl.Map, entry: MarkerEntry | ClusterMarkerEntry) {
    const point = map.project(entry.coordinates).round()

    entry.button.style.transform = `translate(${point.x}px, ${point.y}px) translate(-50%, -50%)`
    entry.marker.addTo(map)
  }

  /** What a place's pin takes up at a level, around its place. */
  function getPinShape(entry: MarkerEntry, level: PinLevel) {
    if (level === 'dot') {
      return DOT_SHAPE
    }

    return level === 'full' && entry.hasPhoto ? PHOTO_SHAPE : BALLOON_SHAPE
  }

  /** The room a pin takes on screen at `point`, its place: its balloon reaches to its tip. */
  function getPinBox(point: mapboxgl.Point, shape: PinShape, balloon: boolean): Box {
    const y = point.y - shape.lift

    return {
      bottom: balloon ? point.y : y + shape.radius,
      left: point.x - shape.radius,
      right: point.x + shape.radius,
      top: y - shape.radius,
    }
  }

  /** The room a balloon's name takes under it. */
  function getNameBox(point: mapboxgl.Point, width: number): Box {
    const top = point.y + LABEL_GAP

    return {
      bottom: top + LABEL_HEIGHT,
      left: point.x - width / 2,
      right: point.x + width / 2,
      top,
    }
  }

  function overlaps(first: Box, second: Box) {
    return (
      first.left < second.right - PIN_TOUCH &&
      second.left < first.right - PIN_TOUCH &&
      first.top < second.bottom - PIN_TOUCH &&
      second.top < first.bottom - PIN_TOUCH
    )
  }

  function setLevel(entry: MarkerEntry, level: PinLevel) {
    if (entry.level === level) {
      return
    }

    entry.level = level
    entry.button.dataset.level = level
    entry.popup.setOffset(getPopupOffset(getPinShape(entry, level)))
  }

  /**
   * Gives every pin in view what room its spot has, the open place's first and then the
   * busiest places', as MapKit makes room for its markers: a photo, or a balloon with its
   * name, where it clears every pin and name already there; else its kind's balloon
   * alone; else its year's dot, which takes no room. The stacks keep theirs. While the
   * map moves a pin only steps down, so none crowd another as a zoom goes out, and none
   * flicker between levels; it takes back what room it has once the map settles.
   */
  function layoutPins(map: mapboxgl.Map, settled = true) {
    const taken: Box[] = []

    for (const cluster of clusterMarkers.values()) {
      if (cluster.button.isConnected) {
        // Its cards behind reach a little past its photo.
        taken.push(
          getPinBox(
            map.project(cluster.coordinates),
            { lift: 0, radius: cluster.radius + STACK_CARDS },
            false,
          ),
        )
      }
    }

    const entries = [...markers.values()]
      .filter((entry) => !entry.button.hidden && entry.button.isConnected)
      .sort(
        (first, second) =>
          Number(second.button.getAttribute('aria-pressed') === 'true') -
            Number(first.button.getAttribute('aria-pressed') === 'true') ||
          second.count - first.count,
      )

    for (const entry of entries) {
      const point = map.project(entry.coordinates)
      const full = entry.hasPhoto
        ? [getPinBox(point, PHOTO_SHAPE, false)]
        : [getPinBox(point, BALLOON_SHAPE, true), getNameBox(point, entry.nameWidth)]
      const icon = [getPinBox(point, BALLOON_SHAPE, true)]
      const pressed = entry.button.getAttribute('aria-pressed') === 'true'
      const fits = (boxes: Box[]) =>
        boxes.every((box) => !taken.some((other) => overlaps(box, other)))
      const room: PinLevel = pressed || fits(full) ? 'full' : fits(icon) ? 'icon' : 'dot'
      const level =
        settled || PIN_LEVELS.indexOf(room) > PIN_LEVELS.indexOf(entry.level) ? room : entry.level

      if (level !== 'dot') {
        taken.push(...(level === 'full' ? full : icon))
      }

      setLevel(entry, level)
    }
  }

  let measureContext: CanvasRenderingContext2D | null | undefined

  /** How wide a balloon's name is set, as styles.css sets it, for the room it takes. */
  function measureName(name: string) {
    measureContext ??= document.createElement('canvas').getContext('2d')

    if (!measureContext) {
      return name.length * 11
    }

    measureContext.font = LABEL_FONT
    return Math.ceil(measureContext.measureText(name).width)
  }

  /**
   * Sets the markers in reading order, row by row from the top, so the keyboard walks
   * them as the eye does rather than in the order they came into view. Left alone
   * while the keyboard is on one of them.
   */
  function orderMarkers(map: mapboxgl.Map) {
    const container = map.getCanvasContainer()
    const entries = [...markers.values(), ...clusterMarkers.values()].filter(
      (entry) => !entry.button.hidden && entry.button.isConnected,
    )

    if (entries.some((entry) => entry.button === document.activeElement)) {
      return
    }

    const ordered = entries
      .map((entry) => {
        const point = map.project(entry.coordinates)
        return { button: entry.button, x: point.x, y: point.y }
      })
      .sort(
        (first, second) =>
          Math.floor(first.y / MARKER_ROW_HEIGHT) - Math.floor(second.y / MARKER_ROW_HEIGHT) ||
          first.x - second.x,
      )
      .map((item) => item.button)
    const current = [
      ...container.querySelectorAll(':scope > .trip-map-marker, :scope > .trip-map-cluster'),
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

  /** A place's or cluster's name on hover, styled like a route's. */
  function createPlacePopup(
    offset: NonNullable<mapboxgl.PopupOptions['offset']>,
    meta: string,
    title: string,
  ) {
    return new mapboxgl.Popup({
      className: MAP_POPUP_CLASS,
      closeButton: false,
      closeOnClick: false,
      focusAfterOpen: false,
      offset,
    }).setDOMContent(createPopupContent({ meta, title }))
  }

  /** A pin's button: no bigger than the pin, or it would cover the pins beside it. */
  function createDotButton(className: string, label: string, color: string) {
    const button = document.createElement('button')

    button.type = 'button'
    button.className = className
    button.setAttribute('aria-label', label)
    button.setAttribute('role', 'button')
    button.style.setProperty('--marker-color', color)
    return button
  }

  /** A photo framed in white with its year's dot at the corner. */
  function createPhoto(src: string, className = 'trip-map-pin-photo') {
    const frame = document.createElement('span')
    const image = document.createElement('img')
    const dot = document.createElement('span')

    frame.className = className
    frame.setAttribute('aria-hidden', 'true')
    image.alt = ''
    // Fetched only once the pin is on the map and near the view.
    image.loading = 'lazy'
    image.decoding = 'async'
    image.src = src
    dot.className = 'trip-map-pin-photo__year'
    frame.append(image, dot)
    return frame
  }

  /** A stack of cards: a photo on top, or the year's colour without one. */
  function createStack(photo: string | undefined) {
    const frame = document.createElement('span')
    const face = document.createElement(photo ? 'img' : 'span')

    frame.className = 'trip-map-pin-photo trip-map-pin-photo--stack'
    frame.setAttribute('aria-hidden', 'true')

    if (face instanceof HTMLImageElement) {
      face.alt = ''
      face.loading = 'lazy'
      face.decoding = 'async'
      face.src = photo!
    } else {
      face.className = 'trip-map-pin-photo__face'
    }

    frame.append(face)
    return frame
  }

  /** A balloon in the year's colour with the kind's icon, its tail on the place. */
  function createBalloon(kind: string | undefined) {
    const balloon = document.createElement('span')

    balloon.className = 'trip-map-pin-balloon'
    balloon.setAttribute('aria-hidden', 'true')
    balloon.innerHTML = `<svg viewBox="0 0 24 24">${getIconBody(getPlaceKindIcon(kind))}</svg>`
    return balloon
  }

  /**
   * The dot on a place under its open pin, as MapKit leaves one where a selected marker
   * lifts off: the pin floats above, and the dot keeps the very spot.
   */
  function createAnchor() {
    const anchor = document.createElement('span')

    anchor.className = 'trip-map-pin-anchor'
    anchor.setAttribute('aria-hidden', 'true')
    return anchor
  }

  function createLabel(name: string, className: string) {
    const label = document.createElement('span')

    label.className = className
    label.setAttribute('aria-hidden', 'true')
    label.textContent = name
    return label
  }

  /** Where a pin's name pops up: above it, or beside or under it near the map's edges. */
  function getPopupOffset({
    lift,
    radius,
  }: PinShape): NonNullable<mapboxgl.PopupOptions['offset']> {
    const gap = 6
    const side = radius + gap

    return {
      bottom: [0, -lift - side],
      'bottom-left': [0, -lift - side],
      'bottom-right': [0, -lift - side],
      center: [0, -lift],
      left: [side, -lift],
      right: [-side, -lift],
      top: [0, -lift + side],
      'top-left': [0, -lift + side],
      'top-right': [0, -lift + side],
    }
  }

  /** Builds every place's marker; `sync` puts the ones in view on the map. */
  function createMarkers() {
    for (const place of places) {
      const momentCount = t(messages, 'momentCount', { count: place.count })
      const button = createDotButton(
        'trip-map-marker',
        t(messages, 'placeLabel', { count: place.count, name: place.name, year: place.firstYear }),
        place.color,
      )
      const level: PinLevel = 'full'
      const popup = createPlacePopup(
        getPopupOffset(place.photo ? PHOTO_SHAPE : BALLOON_SHAPE),
        momentCount,
        place.name,
      )
      // Every level's pin, each shown as its level comes: a photo shows its name only
      // while it's open, lifted over its place, and a balloon's under it at full.
      const dot = document.createElement('span')

      dot.className = 'trip-map-pin-dot'
      dot.setAttribute('aria-hidden', 'true')
      button.setAttribute('aria-pressed', 'false')
      button.dataset.level = level
      // The dot first, under the pins it fades into and out of.
      button.append(
        dot,
        ...(place.photo ? [createPhoto(place.photo)] : []),
        createBalloon(place.kind),
        createAnchor(),
        createLabel(
          place.name,
          place.photo ? 'trip-map-pin-label trip-map-pin-label--open' : 'trip-map-pin-label',
        ),
      )

      const entry: MarkerEntry = {
        button,
        coordinates: [place.longitude, place.latitude],
        count: place.count,
        hasPhoto: Boolean(place.photo),
        level,
        marker: new mapboxgl.Marker({ anchor: 'center', element: button }).setLngLat([
          place.longitude,
          place.latitude,
        ]),
        nameWidth: place.photo ? 0 : measureName(place.name),
        popup,
      }
      const showPopup = () => {
        const map = getMap()

        // The open place's pin, lifted, already has its name under it.
        if (map && !quietFocus && button.getAttribute('aria-pressed') !== 'true') {
          popup.setLngLat(entry.coordinates).addTo(map)
        }
      }
      const hidePopup = () => popup.remove()
      const prefetch = () => onPrefetch(place.id)

      button.addEventListener('mouseenter', showPopup, { signal })
      button.addEventListener('mouseleave', hidePopup, { signal })
      button.addEventListener('focus', showPopup, { signal })
      button.addEventListener('blur', hidePopup, { signal })
      button.addEventListener('pointerenter', prefetch, { signal })
      button.addEventListener('focus', prefetch, { signal })
      button.addEventListener(
        'click',
        (event) => {
          event.stopPropagation()
          hidePopup()
          onSelect(place.id, button)
        },
        { signal },
      )

      markers.set(place.id, entry)
    }
  }

  function isPlaceShown(place: PlaceMarker) {
    return (
      (!tripPlaces || tripPlaces.has(place.id)) &&
      (!period || place.months.some((month) => isInPeriod(month, period!)))
    )
  }

  /** The places the picked period and trip leave on the map; every one without them. */
  function getShownPlaces() {
    return period || tripPlaces ? places.filter(isPlaceShown) : places
  }

  /** Whether the picked period and trip leave this place on the map. */
  function isShown(id: string) {
    const place = placesById.get(id)
    return place !== undefined && isPlaceShown(place)
  }

  function refilter() {
    index = createIndex()
    // The clusters are numbered afresh with the new places.
    clearClusterMarkers()
    sync()
  }

  /** Gathers the shown places at every zoom, as Mapbox's clustered sources do. */
  function createIndex() {
    return new Supercluster<PlaceProperties, ClusterProperties>({
      map: (place) => ({ firstYear: place.firstYear, momentCount: place.count }),
      maxZoom: CLUSTER_MAX_ZOOM,
      minPoints: CLUSTER_MIN_POINTS,
      radius: CLUSTER_RADIUS,
      reduce: (cluster, place) => {
        cluster.firstYear = Math.min(cluster.firstYear, place.firstYear)
        cluster.momentCount += place.momentCount
      },
    }).load(
      getShownPlaces().map((place) => ({
        geometry: { coordinates: [place.longitude, place.latitude], type: 'Point' },
        properties: { count: place.count, firstYear: place.firstYear, id: place.id },
        type: 'Feature',
      })),
    )
  }

  /**
   * Whether a place is left on the map as a gray dot: outside the period picked, it steps
   * back rather than leaving, so the period is still seen among every place. A trip's
   * sidebar keeps only its own places, so none are left then.
   */
  function isDimmed(place: PlaceMarker) {
    return (
      period !== undefined &&
      !tripPlaces &&
      !place.months.some((month) => isInPeriod(month, period!))
    )
  }

  /** Puts the gray dots near the view on the map, under every pin, and takes off the rest. */
  function syncDimDots(map: mapboxgl.Map) {
    const [west, south, east, north] = getNearBounds(map)
    const isNear = (longitude: number, latitude: number) =>
      latitude >= south &&
      latitude <= north &&
      (west <= east
        ? longitude >= west && longitude <= east
        : longitude >= west || longitude <= east)

    for (const place of places) {
      const wanted = isDimmed(place) && isNear(place.longitude, place.latitude)
      const dot = dimDots.get(place.id)

      if (wanted && !dot) {
        const element = document.createElement('span')

        // Named before Mapbox adds its own classes to it.
        element.className = 'trip-map-dim-place'
        element.setAttribute('aria-hidden', 'true')

        const point = map.project([place.longitude, place.latitude]).round()
        const marker = new mapboxgl.Marker({ anchor: 'center', element }).setLngLat([
          place.longitude,
          place.latitude,
        ])

        // Where it belongs from the start, as `placeOnMap` places a pin.
        element.style.transform = `translate(${point.x}px, ${point.y}px) translate(-50%, -50%)`
        marker.addTo(map)
        // Right over the map, under the pins that follow it.
        map.getCanvas().after(element)
        dimDots.set(place.id, marker)
      } else if (!wanted && dot) {
        dot.remove()
        dimDots.delete(place.id)
      }
    }
  }

  function clearDimDots() {
    for (const dot of dimDots.values()) {
      dot.remove()
    }

    dimDots.clear()
  }

  function clearClusterMarkers() {
    for (const cluster of clusterMarkers.values()) {
      cluster.popup.remove()
      cluster.marker.remove()
    }

    clusterMarkers.clear()
  }

  /** The places and clusters within `bounds` (west, south, east, north) at the map's zoom. */
  function getClusters(map: mapboxgl.Map, bounds: [number, number, number, number]) {
    // The zoom whose tiles Mapbox would draw a clustered source with.
    return index.getClusters(bounds, Math.floor(map.getZoom()))
  }

  /**
   * What's in view and as much again around it, so the places about to come in are on
   * the map before a drag brings them in.
   */
  function getNearBounds(map: mapboxgl.Map): [number, number, number, number] {
    const bounds = map.getBounds()

    if (!bounds) {
      return [-180, -90, 180, 90]
    }

    const width = bounds.getEast() - bounds.getWest()
    const height = bounds.getNorth() - bounds.getSouth()
    const south = Math.max(-90, bounds.getSouth() - height / 2)
    const north = Math.min(90, bounds.getNorth() + height / 2)

    if (width * 2 >= 360) {
      return [-180, south, 180, north]
    }

    // Wrapped into -180…180; west past east crosses the antimeridian.
    const wrap = (longitude: number) => ((((longitude + 180) % 360) + 360) % 360) - 180

    return [wrap(bounds.getWest() - width / 2), south, wrap(bounds.getEast() + width / 2), north]
  }

  /**
   * Puts the places and clusters gathered at this zoom on the map, and takes off the
   * rest. `settled` is false while the map moves, when only what's in view needs
   * keeping up.
   */
  function sync(options: { settled?: boolean } = {}) {
    const map = getMap()

    if (isPaused() || !map) {
      return
    }

    const visibleIds = new Set<string>()
    const clusters = new Map<number, ClusterInfo>()

    for (const feature of getClusters(map, getNearBounds(map))) {
      const [longitude, latitude] = feature.geometry.coordinates as [number, number]
      const properties = feature.properties

      if ('cluster_id' in properties) {
        clusters.set(properties.cluster_id, {
          coordinates: [longitude, latitude],
          firstYear: properties.firstYear,
          locationCount: properties.point_count,
          momentCount: properties.momentCount,
        })
      } else {
        visibleIds.add(properties.id)
      }
    }

    // The places whose dot had the focus as it went off the map: a cluster opened from
    // the keyboard, or a place gathered into one.
    let focusedIds: string[] | undefined

    for (const [id, marker] of markers) {
      const shown = visibleIds.has(id)

      if (!shown && marker.button === document.activeElement) {
        focusedIds = [id]
      }

      setMarkerShown(marker, shown)
    }

    // A cluster's id stands for the same places at the same spot, so the ones still
    // shown are kept: a focused cluster keeps its focus, and a hovered one its name.
    for (const [id, cluster] of clusterMarkers) {
      if (!clusters.has(id)) {
        if (cluster.button === document.activeElement) {
          focusedIds = cluster.members.map((member) => member.id)
        }

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

    if (focusedIds) {
      passFocus(focusedIds, visibleIds)
    }

    layoutPins(map, options.settled !== false)
    syncDimDots(map)

    if (options.settled !== false) {
      orderMarkers(map)
    }
  }

  /**
   * Hands the focus on from a dot gone off the map to its busiest place still on it, or
   * to the cluster that place has gathered into, rather than dropping it on the page.
   */
  function passFocus(ids: string[], visibleIds: Set<string>) {
    for (const id of ids) {
      const button = visibleIds.has(id)
        ? markers.get(id)?.button
        : [...clusterMarkers.values()].find((cluster) =>
            cluster.members.some((member) => member.id === id),
          )?.button

      if (button) {
        button.focus({ preventScroll: true })
        return
      }
    }
  }

  /** A cluster's places, busiest first. */
  function getClusterMembers(id: number) {
    let leaves: PointFeature<PlaceProperties>[]

    try {
      leaves = index.getLeaves(id, Infinity)
    } catch {
      // A cluster of the places before the period changed.
      return []
    }

    return leaves
      .map((leaf) => placesById.get(leaf.properties.id))
      .filter((place) => place !== undefined)
      .sort((first, second) => second.count - first.count || first.name.length - second.name.length)
  }

  /** A cluster goes by its busiest place: 上海等. */
  function getClusterName(members: PlaceMarker[]) {
    return t(messages, 'clusterName', { name: members[0]!.name })
  }

  /**
   * Frames a cluster's places close enough to tell apart, in one move, and never short
   * of the zoom it parts at: framing places spread wide, on a phone's narrow screen
   * above all, would leave them gathered as they were.
   */
  function openCluster(map: mapboxgl.Map, id: number, entry: ClusterMarkerEntry) {
    const { members } = entry

    if (members.length === 0) {
      return
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const member of members) {
      bounds.extend([member.longitude, member.latitude])
    }

    const padding = getClusterOpenPadding(map)
    const pitch = getFramingPitch(map, bounds, { maxZoom: CLUSTER_OPEN_MAX_ZOOM, padding })
    const framed = map.cameraForBounds(bounds, {
      maxZoom: CLUSTER_OPEN_MAX_ZOOM,
      padding,
      pitch,
    })
    let partZoom = 0

    try {
      // Past it by a little: the places are gathered at the zoom rounded down.
      partZoom = index.getClusterExpansionZoom(id) + 0.01
    } catch {
      // A cluster of the places before the period changed: framed as it is.
    }

    const framedZoom = framed?.zoom ?? map.getZoom()
    const parting = framedZoom < partZoom

    map.easeTo({
      // Closer than its places' frame, around the cluster, where its parts gather.
      center: parting || !framed?.center ? entry.coordinates : framed.center,
      duration: getAnimationDuration(),
      padding,
      pitch: parting ? getMapPitch(map, partZoom) : pitch,
      zoom: Math.max(framedZoom, partZoom),
    })
  }

  /**
   * Room around a cluster's places as it opens: little at the sides of a phone held
   * upright, and clear of what's open on the map's side.
   */
  function getClusterOpenPadding(map: mapboxgl.Map) {
    const side = map.getContainer().clientWidth <= NARROW_WIDTH ? 32 : CLUSTER_OPEN_PADDING
    const clear = getClearPadding()

    return {
      bottom: Math.max(CLUSTER_OPEN_PADDING, clear.bottom),
      left: Math.max(side, clear.left),
      right: Math.max(side, clear.right),
      top: Math.max(CLUSTER_OPEN_PADDING, clear.top),
    }
  }

  function createClusterMarker(
    map: mapboxgl.Map,
    id: number,
    cluster: ClusterInfo,
  ): ClusterMarkerEntry {
    const members = getClusterMembers(id)
    const meta = t(messages, 'momentCount', { count: cluster.momentCount })
    // Named after its busiest place, on the card and to the screen reader, so it tells
    // where it is without opening it.
    const title =
      members.length > 0
        ? t(messages, 'clusterNamedTitle', {
            count: cluster.locationCount,
            name: getClusterName(members),
          })
        : t(messages, 'clusterTitle', { count: cluster.locationCount })
    const button = createDotButton(
      'trip-map-cluster',
      t(messages, 'clusterLabel', { count: cluster.momentCount, title }),
      yearColors.get(cluster.firstYear) ?? 'var(--color-tx)',
    )
    const popup = createPlacePopup(getPopupOffset(CLUSTER_SHAPE), meta, title)
    const count = document.createElement('span')
    // A stack of its places' photos, the busiest one's on top, or of balloons without one.
    const photo = members.find((member) => member.photo)?.photo

    button.append(createStack(photo))

    count.className = 'trip-map-pin-count'
    count.setAttribute('aria-hidden', 'true')
    count.textContent = String(cluster.momentCount)
    button.append(count)

    const entry: ClusterMarkerEntry = {
      ...CLUSTER_SHAPE,
      button,
      coordinates: cluster.coordinates,
      marker: new mapboxgl.Marker({ anchor: 'center', element: button }).setLngLat(
        cluster.coordinates,
      ),
      members,
      popup,
    }

    placeOnMap(map, entry)

    const showPopup = () => popup.setLngLat(entry.coordinates).addTo(map)
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
        onOpenCluster()
        openCluster(map, id, entry)
      },
      { signal },
    )

    return entry
  }

  // Opening a cluster leaves the view that framed every place.
  let onOpenCluster = () => {}

  /**
   * The places a screenshot draws, gathered as the map gathers them at its framing:
   * each cluster named after its busiest place, with every place in it.
   */
  function getScreenshotPlaces(map: mapboxgl.Map): ScreenshotPlace[] {
    const bounds = map.getBounds()
    const canvas = map.getCanvas()

    if (!bounds) {
      return []
    }

    return getClusters(map, [
      bounds.getWest(),
      bounds.getSouth(),
      bounds.getEast(),
      bounds.getNorth(),
    ]).flatMap((feature): ScreenshotPlace[] => {
      const [longitude, latitude] = feature.geometry.coordinates as [number, number]
      const { x, y } = map.project([longitude, latitude])

      // Only what the picture shows.
      if (x < 0 || y < 0 || x > canvas.clientWidth || y > canvas.clientHeight) {
        return []
      }

      const properties = feature.properties

      if (!('cluster_id' in properties)) {
        const place = placesById.get(properties.id)
        return place ? [place] : []
      }

      const members = getClusterMembers(properties.cluster_id)

      return [
        {
          color: yearColors.get(properties.firstYear) ?? members[0]?.color ?? '#888',
          count: properties.momentCount,
          firstYear: properties.firstYear,
          latitude,
          longitude,
          members,
          name:
            members.length > 0
              ? getClusterName(members)
              : t(messages, 'clusterTitle', { count: properties.point_count }),
        },
      ]
    })
  }

  createMarkers()

  return {
    destroy() {
      for (const marker of markers.values()) {
        marker.popup.remove()
        marker.marker.remove()
      }

      clearClusterMarkers()
      clearDimDots()
    },
    /** Focuses a place's dot without popping its name up, as the drawer closes. */
    focusQuietly(button: HTMLElement) {
      quietFocus = true

      try {
        button.focus({ preventScroll: true })
      } finally {
        quietFocus = false
      }
    },
    getButton: (id: string) => markers.get(id)?.button,
    getPlace: (id: string) => placesById.get(id),
    getScreenshotPlaces,
    getShownPlaces,
    has: (id: string) => placesById.has(id),
    isShown,
    /** Takes every marker off the map (as a trip opens) or puts them back. */
    setHidden(hidden: boolean) {
      for (const [id, marker] of markers) {
        setMarkerShown(marker, !hidden && isShown(id))
      }

      if (hidden) {
        clearClusterMarkers()
        clearDimDots()
      } else {
        sync()
      }
    },
    set onOpenCluster(handler: () => void) {
      onOpenCluster = handler
    },
    /** Marks the open place's dot as pressed, or none. */
    setPressed(id: string | undefined) {
      const map = getMap()

      for (const [markerId, marker] of markers) {
        marker.button.setAttribute('aria-pressed', String(markerId === id))
      }

      // The open place gets its room first.
      if (map && !isPaused()) {
        layoutPins(map)
      }
    },
    sync,
    get period() {
      return period
    },
    /**
     * Leaves only the places with Moments in `next` on the map, or every place again,
     * gathered afresh.
     */
    setPeriod(next: Period | undefined) {
      period = next
      refilter()
    },
    /** Leaves only a trip's own places on the map, or every place again. */
    setTripPlaces(next: readonly string[] | undefined) {
      if (!next && !tripPlaces) {
        return
      }

      tripPlaces = next && new Set(next)
      refilter()
    },
  }
}

/**
 * The view framing every place, as close as their spread allows (a single place as
 * close as it goes, or `maxZoom`), leaving room for the
 * toolbar and the legend, and `inset` besides (a panel over the map). `inset.legend` is
 * how far up from the map's foot the legend reaches, when it's over the map.
 */
export function getAllPlacesView(
  places: readonly Readonly<{ latitude: number; longitude: number }>[],
  narrow: boolean,
  inset: FramingInset = {},
  maxZoom = ALL_PLACES_MAX_ZOOM,
) {
  const bounds = new mapboxgl.LngLatBounds()

  for (const place of places) {
    bounds.extend([place.longitude, place.latitude])
  }

  return {
    bounds,
    fitBoundsOptions: {
      maxZoom,
      padding: getAllPlacesPadding(narrow, inset),
    },
  }
}
