import mapboxgl from 'mapbox-gl'
import Supercluster from 'supercluster'
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

// The places on the map: a dot per place, coloured by the year it was last visited,
// gathered into clusters where they crowd at the zoom shown. The clusters are worked
// out here rather than by a clustered source, whose tiles mid-zoom mix two zooms' worth
// of them: here they're always the zoom's own, so they part and join as a zoom goes. The
// dots are buttons placed over the map, so they can be hovered, focused and pressed like
// any other.

export type { PlaceMarker }
export { CLUSTER_MAX_ZOOM }

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
  firstYear: number
  locationCount: number
  momentCount: number
}

type ClusterMarkerEntry = {
  button: HTMLButtonElement
  coordinates: [number, number]
  dot: HTMLElement
  marker: mapboxgl.Marker
  /** Busiest first. */
  members: PlaceMarker[]
  nudge: Nudge
  popup: mapboxgl.Popup
  radius: number
}

// How close on screen, in pixels of a 512-pixel tile as Mapbox draws them, places are
// gathered into one cluster, and how many it takes: two places side by side aren't a
// crowd, so they stay as they are, even if one covers part of the other.
const CLUSTER_RADIUS = 20
const CLUSTER_MIN_POINTS = 3
// The dots' radii on screen, in pixels (a place with one Moment, one with more, and a
// cluster), and the gap kept between dots pushed apart so none covers another.
const DOT_RADIUS = { cluster: 12, place: 9, single: 7 }
const DOT_GAP = 2
const DOT_SPREAD_ROUNDS = 8
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
      entry.marker.addTo(map)
    } else {
      entry.popup.remove()
      entry.marker.remove()
    }
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

  // How wide each kind of dot is with a number of so many digits, measured the first
  // time one is shown: measuring every new cluster as a zoom goes would have the page
  // laid out again in the middle of its frames.
  const dotRadii = new Map<string, number>()

  /**
   * Pushes dots that would cover one another apart on screen, each a little, until
   * every one shows: two places side by side stay two dots, and a place beside a
   * cluster keeps clear of it.
   */
  function spreadDots(map: mapboxgl.Map) {
    const dots = [...markers.values(), ...clusterMarkers.values()]
      .filter((entry) => !entry.button.hidden && entry.button.isConnected)
      .map((entry) => {
        const point = map.project(entry.coordinates)
        const key = `${entry.dot.className}:${entry.dot.textContent.length}`
        let radius = dotRadii.get(key)

        if (radius === undefined) {
          // A cluster's number widens its dot.
          radius = Math.max(entry.radius, entry.dot.offsetWidth / 2)
          dotRadii.set(key, radius)
        }

        return {
          entry,
          radius,
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
      setNudge(map, dot.entry, Math.round(dot.x - dot.x0), Math.round(dot.y - dot.y0))
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
  function createPlacePopup(offset: number, meta: string, title: string) {
    return new mapboxgl.Popup({
      className: MAP_POPUP_CLASS,
      closeButton: false,
      closeOnClick: false,
      focusAfterOpen: false,
      offset,
    }).setDOMContent(createPopupContent({ meta, title }))
  }

  /** A dot's button: no bigger than the dot and a thin ring, or it would cover the dots beside it. */
  function createDotButton(className: string, label: string, color: string) {
    const button = document.createElement('button')

    button.type = 'button'
    button.className = className
    button.setAttribute('aria-label', label)
    button.setAttribute('role', 'button')
    button.style.setProperty('--marker-color', color)
    return button
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
      const dot = document.createElement('span')
      const popup = createPlacePopup(12, momentCount, place.name)

      button.setAttribute('aria-pressed', 'false')
      // A single Moment is a smaller dot, without a number.
      dot.className =
        'trip-map-marker__dot' + (place.count > 1 ? ' trip-map-marker__dot--many' : '')
      dot.setAttribute('aria-hidden', 'true')
      dot.textContent = place.count > 1 ? String(place.count) : ''
      button.append(dot)

      const entry: MarkerEntry = {
        button,
        coordinates: [place.longitude, place.latitude],
        dot,
        marker: new mapboxgl.Marker({ anchor: 'center', element: button }).setLngLat([
          place.longitude,
          place.latitude,
        ]),
        nudge: { x: 0, y: 0 },
        popup,
        radius: place.count > 1 ? DOT_RADIUS.place : DOT_RADIUS.single,
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
    return !period || place.months.some((month) => isInPeriod(month, period!))
  }

  /** The places the picked period leaves on the map; every one without a period. */
  function getShownPlaces() {
    return period ? places.filter(isPlaceShown) : places
  }

  /** Whether the picked period leaves this place on the map. */
  function isShown(id: string) {
    const place = placesById.get(id)
    return place !== undefined && isPlaceShown(place)
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

    for (const [id, marker] of markers) {
      setMarkerShown(marker, visibleIds.has(id))
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

    spreadDots(map)

    if (options.settled !== false) {
      orderMarkers(map)
    }
  }

  /** A cluster's places, busiest first. */
  function getClusterMembers(id: number) {
    let leaves: Supercluster.PointFeature<PlaceProperties>[]

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
    const dot = document.createElement('span')
    const popup = createPlacePopup(16, meta, title)

    dot.className = 'trip-map-cluster__dot'
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
      members,
      nudge: { x: 0, y: 0 },
      popup,
      radius: DOT_RADIUS.cluster,
    }
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
      index = createIndex()
      // The clusters are numbered afresh with the new places.
      clearClusterMarkers()
      sync()
    },
  }
}

/**
 * The view framing every place, as close as their spread allows (a single place as
 * close as it goes), leaving room for the
 * toolbar and the legend, and `inset` besides (a panel over the map). `inset.legend` is
 * how far up from the map's foot the legend reaches, when it's over the map.
 */
export function getAllPlacesView(
  places: readonly PlaceMarker[],
  narrow: boolean,
  inset: FramingInset = {},
) {
  const bounds = new mapboxgl.LngLatBounds()

  for (const place of places) {
    bounds.extend([place.longitude, place.latitude])
  }

  return {
    bounds,
    fitBoundsOptions: {
      maxZoom: ALL_PLACES_MAX_ZOOM,
      padding: getAllPlacesPadding(narrow, inset),
    },
  }
}
