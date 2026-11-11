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
// them: here they're always the zoom's own, so they part and join as a zoom goes. The
// pins are buttons placed over the map, so they can be hovered, focused and pressed like
// any other.

export type { PlaceMarker }
export { CLUSTER_MAX_ZOOM }

/** How far a pin is pushed on screen, in pixels, to clear the pins beside it. */
type Nudge = { x: number; y: number }

/** Where a pin is on screen: around its place, or a balloon's tail on it. */
type PinShape = {
  anchor: 'bottom' | 'center'
  /** How far above its place the pin's middle is, in pixels. */
  lift: number
  radius: number
}

type MarkerEntry = PinShape & {
  button: HTMLButtonElement
  coordinates: [number, number]
  count: number
  /** A balloon's name under it, shown where it clears the other pins and names. */
  label?: HTMLElement
  labelWidth?: number
  marker: mapboxgl.Marker
  nudge: Nudge
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
  nudge: Nudge
  popup: mapboxgl.Popup
}

// How close on screen, in pixels of a 512-pixel tile as Mapbox draws them, places are
// gathered into one cluster, and how many it takes: two places side by side aren't a
// crowd, so they stay as they are, even if one covers part of the other.
const CLUSTER_RADIUS = 32
const CLUSTER_MIN_POINTS = 3
// The pins' sizes on screen, in pixels, as styles.css draws them: a photo's side, a
// balloon's circle and its tail, and a stack's photo.
const PIN = { balloon: 28, cluster: 38, photo: 34, tail: 6 }
const PHOTO_SHAPE: PinShape = { anchor: 'center', lift: 0, radius: PIN.photo / 2 }
const BALLOON_SHAPE: PinShape = {
  anchor: 'bottom',
  lift: PIN.tail + PIN.balloon / 2,
  radius: PIN.balloon / 2,
}
const CLUSTER_SHAPE: PinShape = { anchor: 'center', lift: 0, radius: PIN.cluster / 2 }
// How much of two pins may overlap before they're pushed apart: they crowd as MapKit's
// do, each still showing enough to tell it by.
const PIN_OVERLAP = 0.3
const PIN_SPREAD_ROUNDS = 8
// A balloon's name: how tall a line, and the gap kept around it.
const LABEL_HEIGHT = 14
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
    const anchor = entry.anchor === 'bottom' ? '-50%, -100%' : '-50%, -50%'

    entry.button.style.transform = `translate(${point.x}px, ${point.y}px) translate(${anchor}) translate(${entry.nudge.x}px, ${entry.nudge.y}px)`
    entry.marker.addTo(map)
  }

  /** Where a dot sits once pushed aside, for its name to hang over. */
  function getNudgedLngLat(map: mapboxgl.Map, coordinates: [number, number], nudge: Nudge) {
    const point = map.project(coordinates)
    point.x += nudge.x
    point.y += nudge.y
    return map.unproject(point)
  }

  function setNudge(
    map: mapboxgl.Map,
    entry: MarkerEntry | ClusterMarkerEntry,
    x: number,
    y: number,
  ) {
    if (entry.nudge.x === x && entry.nudge.y === y) {
      return
    }

    entry.nudge = { x, y }
    entry.marker.setOffset([x, y])

    if (entry.popup.isOpen()) {
      entry.popup.setLngLat(getNudgedLngLat(map, entry.coordinates, entry.nudge))
    }
  }

  /**
   * Pushes pins that would mostly cover one another apart on screen, each a little,
   * until every one shows enough to tell it by: two places side by side stay two pins,
   * and a place beside a stack keeps clear of it.
   */
  function spreadDots(map: mapboxgl.Map) {
    const dots = [...markers.values(), ...clusterMarkers.values()]
      .filter((entry) => !entry.button.hidden && entry.button.isConnected)
      .map((entry) => {
        const point = map.project(entry.coordinates)
        const y = point.y - entry.lift

        return { entry, x: point.x, x0: point.x, y, y0: y }
      })

    for (let round = 0; round < PIN_SPREAD_ROUNDS; round++) {
      let moved = false

      for (let i = 0; i < dots.length; i++) {
        for (let j = i + 1; j < dots.length; j++) {
          const first = dots[i]!
          const second = dots[j]!
          const minimum = (first.entry.radius + second.entry.radius) * (1 - PIN_OVERLAP)
          let dx = second.x - first.x
          let dy = second.y - first.y
          let distance = Math.hypot(dx, dy)

          if (distance >= minimum) {
            continue
          }

          // Two pins on the very same spot part sideways.
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
      setNudge(map, dot.entry, Math.round(dot.x - dot.x0), Math.round(dot.y - dot.y0))
    }

    placeLabels(
      map,
      dots.map((dot) => dot.entry),
    )
  }

  /**
   * Shows the balloons' names where they clear every pin and the names already shown,
   * the busiest places' first, as MapKit leaves out the names it has no room for.
   */
  function placeLabels(map: mapboxgl.Map, entries: (MarkerEntry | ClusterMarkerEntry)[]) {
    type Box = { bottom: number; left: number; right: number; top: number }
    const boxes = new Map<MarkerEntry | ClusterMarkerEntry, Box>()

    for (const entry of entries) {
      const point = map.project(entry.coordinates)
      const x = point.x + entry.nudge.x
      const y = point.y + entry.nudge.y - entry.lift

      boxes.set(entry, {
        // A balloon reaches down to its tail's tip.
        bottom: y + entry.radius + (entry.anchor === 'bottom' ? PIN.tail : 0),
        left: x - entry.radius,
        right: x + entry.radius,
        top: y - entry.radius,
      })
    }

    const overlaps = (first: Box, second: Box) =>
      first.left < second.right + LABEL_GAP &&
      second.left < first.right + LABEL_GAP &&
      first.top < second.bottom + LABEL_GAP &&
      second.top < first.bottom + LABEL_GAP
    const shown: Box[] = []
    const labelled = entries
      .filter((entry): entry is MarkerEntry => 'label' in entry && entry.label !== undefined)
      .sort((first, second) => second.count - first.count)

    for (const entry of labelled) {
      const label = entry.label!
      // Measured once, the first time it's on the map.
      entry.labelWidth ??= label.offsetWidth
      const point = map.project(entry.coordinates)
      const x = point.x + entry.nudge.x
      const top = point.y + entry.nudge.y + LABEL_GAP
      const box = {
        bottom: top + LABEL_HEIGHT,
        left: x - entry.labelWidth / 2,
        right: x + entry.labelWidth / 2,
        top,
      }
      const clear =
        !shown.some((other) => overlaps(box, other)) &&
        ![...boxes].some(([other, otherBox]) => other !== entry && overlaps(box, otherBox))

      if (clear) {
        shown.push(box)
      }

      if (label.hidden === clear) {
        label.hidden = !clear
      }
    }
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
        return { button: entry.button, x: point.x + entry.nudge.x, y: point.y + entry.nudge.y }
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
      const shape = place.photo ? PHOTO_SHAPE : BALLOON_SHAPE
      const button = createDotButton(
        'trip-map-marker',
        t(messages, 'placeLabel', { count: place.count, name: place.name, year: place.firstYear }),
        place.color,
      )
      const popup = createPlacePopup(getPopupOffset(shape), momentCount, place.name)
      let label: HTMLElement | undefined

      button.setAttribute('aria-pressed', 'false')

      if (place.photo) {
        button.append(createPhoto(place.photo))
      } else {
        label = document.createElement('span')
        label.className = 'trip-map-pin-label'
        label.setAttribute('aria-hidden', 'true')
        label.textContent = place.name
        label.hidden = true
        button.append(createBalloon(place.kind), label)
      }

      const entry: MarkerEntry = {
        ...shape,
        button,
        coordinates: [place.longitude, place.latitude],
        count: place.count,
        ...(label && { label }),
        marker: new mapboxgl.Marker({ anchor: shape.anchor, element: button }).setLngLat([
          place.longitude,
          place.latitude,
        ]),
        nudge: { x: 0, y: 0 },
        popup,
      }
      const showPopup = () => {
        const map = getMap()

        if (map && !quietFocus) {
          popup.setLngLat(getNudgedLngLat(map, entry.coordinates, entry.nudge)).addTo(map)
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

    spreadDots(map)

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
      nudge: { x: 0, y: 0 },
      popup,
    }

    placeOnMap(map, entry)

    const showPopup = () =>
      popup.setLngLat(getNudgedLngLat(map, entry.coordinates, entry.nudge)).addTo(map)
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
      } else {
        sync()
      }
    },
    set onOpenCluster(handler: () => void) {
      onOpenCluster = handler
    },
    /** Marks the open place's dot as pressed, or none. */
    setPressed(id: string | undefined) {
      for (const [markerId, marker] of markers) {
        marker.button.setAttribute('aria-pressed', String(markerId === id))
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
