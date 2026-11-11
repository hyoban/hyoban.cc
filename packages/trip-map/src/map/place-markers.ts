import mapboxgl from 'mapbox-gl'
import Supercluster, { type PointFeature } from 'supercluster'
import { getIconBody, getPlaceKindIcon } from '../icons.ts'
import { t, type Messages } from '../messages.ts'
import { createPopupContent, MAP_POPUP_CLASS } from '../player/dom.ts'
import type { PlaceMarker } from '../player/types.ts'
import { isInPeriod, type Period } from './period.ts'
import type { ScreenshotPlace } from './screenshot.ts'
import {
  ALL_PLACES_MAX_ZOOM,
  CLUSTER_MAX_ZOOM,
  getAllPlacesPadding,
  type FramingInset,
} from '../static-map.ts'

// The places on the map, drawn as the Mac app's MapKit draws them: the newest photo taken
// at a place, framed in white with its year's dot at the corner, or for a place without
// one a balloon in its year's colour with its kind's icon and its name under it where
// there's room. Every pin stays on its very spot, and where places crowd their pins pile
// up there as MapKit's do, the busiest on top. The pins are buttons placed over the map,
// so they can be hovered, focused and pressed like any other.

export type { PlaceMarker }
export { CLUSTER_MAX_ZOOM }

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
  /** A balloon's name under it, shown where it clears the pins and names around it. */
  label?: HTMLElement
  marker: mapboxgl.Marker
  /** How wide its name is under a balloon, measured once. */
  nameWidth: number
  popup: mapboxgl.Popup
  shape: PinShape
}

type Box = { bottom: number; left: number; right: number; top: number }

// How close on screen, in pixels of a 512-pixel tile as Mapbox draws them, places are
// gathered into one in a screenshot, and how many it takes.
const CLUSTER_RADIUS = 32
const CLUSTER_MIN_POINTS = 3
// The pins' sizes on screen, in pixels, as styles.css draws them: a photo's side, and a
// balloon's circle and its tail.
const PIN = { balloon: 28, photo: 34, tail: 6 }
const PHOTO_SHAPE: PinShape = { lift: 0, radius: PIN.photo / 2 }
const BALLOON_SHAPE: PinShape = { lift: PIN.tail + PIN.balloon / 2, radius: PIN.balloon / 2 }
// How far a name may reach into a pin and still be read, in pixels.
const PIN_TOUCH = 2
// A balloon's name: how tall a line, its type as styles.css sets it, and the gap above it.
const LABEL_HEIGHT = 14
const LABEL_FONT = '600 11px system-ui, sans-serif'
const LABEL_GAP = 2
// The markers are set in reading order for the keyboard, in rows this tall.
const MARKER_ROW_HEIGHT = 48

type PlaceProperties = { count: number; firstYear: number; id: string }
type ClusterProperties = { firstYear: number; momentCount: number }

export type PlaceMarkers = ReturnType<typeof createPlaceMarkers>

export function createPlaceMarkers({
  getMap,
  isPaused,
  messages,
  onPrefetch,
  onSelect,
  places,
  signal,
}: {
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
  // The places outside the period picked, left on the map as gray dots near the view.
  const dimDots = new Map<string, mapboxgl.Marker>()
  // The year or month picked: only the places with Moments then are on the map.
  let period: Period | undefined
  // The places of the trip whose sidebar is open: the only ones left on the map then.
  let tripPlaces: ReadonlySet<string> | undefined
  // Focus put back on a marker as the drawer closes shouldn't pop its name up.
  let quietFocus = false
  // The shown places, gathered at each zoom for a screenshot.
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
  function placeOnMap(map: mapboxgl.Map, entry: MarkerEntry) {
    const point = map.project(entry.coordinates).round()

    entry.button.style.transform = `translate(${point.x}px, ${point.y}px) translate(-50%, -50%)`
    entry.marker.addTo(map)
  }

  /** The room a pin takes on screen at `point`, its place: a balloon reaches to its tip. */
  function getPinBox(point: mapboxgl.Point, shape: PinShape): Box {
    const y = point.y - shape.lift

    return {
      bottom: shape.lift > 0 ? point.y : y + shape.radius,
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

  /**
   * Piles the pins in view on their spots as MapKit does, the open place's on top and
   * then the busiest places', and shows a balloon's name where it clears every pin and
   * the names over it, as MapKit leaves out the titles it has no room for.
   */
  function layoutPins(map: mapboxgl.Map) {
    const entries = [...markers.values()]
      .filter((entry) => !entry.button.hidden && entry.button.isConnected)
      .sort(
        (first, second) =>
          Number(second.button.getAttribute('aria-pressed') === 'true') -
            Number(first.button.getAttribute('aria-pressed') === 'true') ||
          second.count - first.count,
      )
    const points = new Map(entries.map((entry) => [entry, map.project(entry.coordinates)]))
    const pins = entries.map((entry) => getPinBox(points.get(entry)!, entry.shape))
    const names: Box[] = []

    entries.forEach((entry, rank) => {
      const z = String(entries.length - rank)

      // Synced every frame of a move: only what changes is touched.
      if (entry.button.style.getPropertyValue('--pin-z') !== z) {
        entry.button.style.setProperty('--pin-z', z)
      }

      if (!entry.label) {
        return
      }

      const box = getNameBox(points.get(entry)!, entry.nameWidth)
      const clear =
        entry.button.getAttribute('aria-pressed') === 'true' ||
        (!names.some((other) => overlaps(box, other)) &&
          !pins.some((other, index) => index !== rank && overlaps(box, other)))

      if (clear) {
        names.push(box)
      }

      if (entry.label.hidden === clear) {
        entry.label.hidden = !clear
      }
    })
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
    const entries = [...markers.values()].filter(
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
    const current = [...container.querySelectorAll(':scope > .trip-map-marker')]

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

  /** A place's name on hover, styled like a route's. */
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
      const shape = place.photo ? PHOTO_SHAPE : BALLOON_SHAPE
      const popup = createPlacePopup(getPopupOffset(shape), momentCount, place.name)
      // A photo shows its name only while it's open, lifted over its place.
      const label = createLabel(
        place.name,
        place.photo ? 'trip-map-pin-label trip-map-pin-label--open' : 'trip-map-pin-label',
      )

      button.setAttribute('aria-pressed', 'false')
      button.append(
        place.photo ? createPhoto(place.photo) : createBalloon(place.kind),
        createAnchor(),
        label,
      )

      const entry: MarkerEntry = {
        button,
        coordinates: [place.longitude, place.latitude],
        count: place.count,
        ...(!place.photo && { label }),
        marker: new mapboxgl.Marker({ anchor: 'center', element: button }).setLngLat([
          place.longitude,
          place.latitude,
        ]),
        nameWidth: place.photo ? 0 : measureName(place.name),
        popup,
        shape,
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
    sync()
  }

  /** Gathers the shown places at every zoom for a screenshot, as Mapbox's clustered sources do. */
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
    const isNear = getIsNear(map)

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

  /** Whether a place is near enough the view to be on the map: in it, or about to come in. */
  function getIsNear(map: mapboxgl.Map) {
    const [west, south, east, north] = getNearBounds(map)

    return (longitude: number, latitude: number) =>
      latitude >= south &&
      latitude <= north &&
      (west <= east
        ? longitude >= west && longitude <= east
        : longitude >= west || longitude <= east)
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
   * Puts the shown places near the view on the map, and takes off the rest. `settled` is
   * false while the map moves, when only what's in view needs keeping up.
   */
  function sync(options: { settled?: boolean } = {}) {
    const map = getMap()

    if (isPaused() || !map) {
      return
    }

    const isNear = getIsNear(map)

    for (const [id, marker] of markers) {
      setMarkerShown(marker, isShown(id) && isNear(...marker.coordinates))
    }

    layoutPins(map)
    syncDimDots(map)

    if (options.settled !== false) {
      orderMarkers(map)
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
   * The places a screenshot draws, gathered where they crowd at its framing: each
   * cluster named after its busiest place, with every place in it.
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
        clearDimDots()
      } else {
        sync()
      }
    },
    /** Marks the open place's dot as pressed, or none. */
    setPressed(id: string | undefined) {
      const map = getMap()

      for (const [markerId, marker] of markers) {
        marker.button.setAttribute('aria-pressed', String(markerId === id))
      }

      // The open place goes on top of the pile.
      if (map && !isPaused()) {
        layoutPins(map)
      }
    },
    sync,
    get period() {
      return period
    },
    /**
     * Leaves only the places with Moments in `next` on the map, the others as gray dots,
     * or every place again.
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
