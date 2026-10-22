import mapboxgl, { type GeoJSONSource } from 'mapbox-gl'
import { t, type Messages } from '../messages.ts'
import { createPopupContent, MAP_POPUP_CLASS } from '../player/dom.ts'
import type { PlaceMarker } from '../player/types.ts'
import { isInPeriod, type Period } from './period.ts'
import type { ScreenshotPlace } from './screenshot.ts'

// The places on the map: a dot per place, coloured by the year it was last visited,
// gathered into clusters where they crowd at the zoom shown. Clustering is done by
// a hidden GeoJSON source, and the dots are buttons placed over it, so they can be
// hovered, focused and pressed like any other.

export type { PlaceMarker }

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
  /** Busiest first, once they've been looked up. */
  members?: PlaceMarker[]
  nudge: Nudge
  popup: mapboxgl.Popup
  radius: number
}

const INDEX_LAYER_ID = 'trip-map-place-index'
const SOURCE_ID = 'trip-map-places'
export const CLUSTER_MAX_ZOOM = 9
// How close on screen, in pixels, places are gathered into one cluster, and how many
// it takes: two places side by side aren't a crowd, so they stay as they are, even
// if one covers part of the other.
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
// Framing every place, or a period's, the map comes as close as their spread allows, but
// no closer than one city's places standing apart, out of their clusters.
const ALL_PLACES_MAX_ZOOM = CLUSTER_MAX_ZOOM + 1
// Between the legend and the lowest place framed: a cluster's radius and some.
const LEGEND_GAP = 28
// The markers are set in reading order for the keyboard, in rows this tall.
const MARKER_ROW_HEIGHT = 48

export type PlaceMarkers = ReturnType<typeof createPlaceMarkers>

export function createPlaceMarkers({
  getAnimationDuration,
  getMap,
  isPaused,
  messages,
  onPrefetch,
  onSelect,
  places,
  signal,
}: {
  getAnimationDuration: () => number
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

  /**
   * Takes a hidden marker off the map rather than only hiding it: Mapbox repositions
   * every marker on the map each frame it draws, which adds up during trip playback.
   */
  function setMarkerShown(entry: MarkerEntry, shown: boolean) {
    const map = getMap()

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
   * Pushes dots that would cover one another apart on screen, each a little, until
   * every one shows: two places side by side stay two dots, and a place beside a
   * cluster keeps clear of it.
   */
  function spreadDots(map: mapboxgl.Map) {
    const dots = [...markers.values(), ...clusterMarkers.values()]
      .filter((entry) => !entry.button.hidden && entry.button.isConnected)
      .map((entry) => {
        const point = map.project(entry.coordinates)

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

  function getIndexData(): GeoJSON.FeatureCollection {
    return {
      features: getShownPlaces().map((place) => ({
        geometry: {
          coordinates: [place.longitude, place.latitude],
          type: 'Point' as const,
        },
        properties: { count: place.count, id: place.id, firstYear: place.firstYear },
        type: 'Feature' as const,
      })),
      type: 'FeatureCollection',
    }
  }

  /** The hidden source the map clusters the places with. Adds it unless it is there. */
  function addIndex() {
    const map = getMap()

    if (!map || map.getSource(SOURCE_ID)) {
      return
    }

    // A new source numbers its clusters afresh; the next sync draws them.
    clearClusterMarkers()

    map.addSource(SOURCE_ID, {
      cluster: true,
      clusterMaxZoom: CLUSTER_MAX_ZOOM,
      clusterMinPoints: CLUSTER_MIN_POINTS,
      clusterProperties: {
        firstYear: ['min', ['get', 'firstYear']],
        momentCount: ['+', ['get', 'count']],
      },
      clusterRadius: CLUSTER_RADIUS,
      data: getIndexData(),
      type: 'geojson',
    })
    map.addLayer({
      id: INDEX_LAYER_ID,
      paint: { 'circle-opacity': 0, 'circle-radius': 1 },
      source: SOURCE_ID,
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
  function sync(options: { settled?: boolean } = {}) {
    const map = getMap()

    if (isPaused() || !map || !map.getSource(SOURCE_ID) || !map.isSourceLoaded(SOURCE_ID)) {
      return
    }

    const visibleIds = new Set<string>()
    const clusters = new Map<number, ClusterInfo>()

    // The source's loaded tiles reach past the view, so the places about to come in
    // are on the map before a drag brings them in.
    for (const feature of map.querySourceFeatures(SOURCE_ID)) {
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
            firstYear: Number(properties.firstYear) || 0,
            locationCount: Number(properties.point_count) || 0,
            momentCount: Number(properties.momentCount) || 0,
          })
        }
      } else if (typeof properties.id === 'string') {
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
  function getClusterMembers(map: mapboxgl.Map, id: number) {
    const source = map.getSource(SOURCE_ID) as GeoJSONSource | undefined

    if (!source) {
      return Promise.resolve<PlaceMarker[]>([])
    }

    return new Promise<PlaceMarker[]>((resolve) =>
      source.getClusterLeaves(id, Infinity, 0, (error, features) =>
        resolve(
          error
            ? []
            : (features ?? [])
                .map((leaf) => placesById.get(String(leaf.properties?.id)))
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
  function getClusterName(members: PlaceMarker[]) {
    return t(messages, 'clusterName', { name: members[0]!.name })
  }

  /**
   * Frames a cluster's places close enough to tell apart, in one move; a cluster
   * whose places can't be looked up opens one step at a time instead.
   */
  async function openCluster(map: mapboxgl.Map, id: number, entry: ClusterMarkerEntry) {
    const members = entry.members ?? (await getClusterMembers(map, id))

    if (members.length === 0) {
      const source = map.getSource(SOURCE_ID) as GeoJSONSource | undefined
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
    const meta = t(messages, 'momentCount', { count: cluster.momentCount })
    const title = t(messages, 'clusterTitle', { count: cluster.locationCount })
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

      const namedTitle = t(messages, 'clusterNamedTitle', {
        count: cluster.locationCount,
        name: getClusterName(members),
      })

      entry.members = members
      popup.setDOMContent(createPopupContent({ meta, title: namedTitle }))
      button.setAttribute(
        'aria-label',
        t(messages, 'clusterLabel', { count: cluster.momentCount, title: namedTitle }),
      )
    })

    return entry
  }

  // Opening a cluster leaves the view that framed every place.
  let onOpenCluster = () => {}

  /**
   * The places a screenshot draws, gathered as the map gathers them at its framing:
   * each cluster named after its busiest place, with every place in it.
   */
  async function getScreenshotPlaces(map: mapboxgl.Map): Promise<ScreenshotPlace[]> {
    const shot: ScreenshotPlace[] = []
    const seen = new Set<string>()

    for (const feature of map.queryRenderedFeatures({ layers: [INDEX_LAYER_ID] })) {
      const properties = feature.properties ?? {}

      if (feature.geometry.type !== 'Point') {
        continue
      }

      if (!properties.cluster) {
        const place = placesById.get(String(properties.id))

        if (place && !seen.has(place.id)) {
          seen.add(place.id)
          shot.push(place)
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
      const firstYear = Number(properties.firstYear) || 0

      shot.push({
        color: yearColors.get(firstYear) ?? members[0]?.color ?? '#888',
        count: Number(properties.momentCount) || 0,
        firstYear,
        latitude: latitude!,
        longitude: longitude!,
        members,
        name:
          members.length > 0
            ? getClusterName(members)
            : t(messages, 'clusterTitle', { count: Number(properties.point_count) || 0 }),
      })
    }

    return shot
  }

  createMarkers()

  return {
    addIndex,
    destroy() {
      for (const marker of markers.values()) {
        marker.popup.remove()
        marker.marker.remove()
      }

      clearClusterMarkers()
    },
    /** Focuses a place's dot without popping its name up, as the drawer closes. */
    focusQuietly(button: HTMLButtonElement) {
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
     * Leaves only the places with Moments in `next` on the map, or every place again;
     * the next sync, once the source has gathered them afresh, puts them on.
     */
    setPeriod(next: Period | undefined) {
      period = next

      const source = getMap()?.getSource(SOURCE_ID) as GeoJSONSource | undefined

      if (!source) {
        return
      }

      // The clusters are numbered afresh with the new data.
      clearClusterMarkers()

      for (const [id, marker] of markers) {
        if (!isShown(id)) {
          setMarkerShown(marker, false)
        }
      }

      source.setData(getIndexData())
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
  inset: { bottom?: number; legend?: number; right?: number } = {},
) {
  const bounds = new mapboxgl.LngLatBounds()

  for (const place of places) {
    bounds.extend([place.longitude, place.latitude])
  }

  // A phone held upright is short of width, so the sides get only a little room.
  const side = narrow ? 20 : 56

  return {
    bounds,
    fitBoundsOptions: {
      maxZoom: ALL_PLACES_MAX_ZOOM,
      padding: {
        // A panel along the foot is clear of the legend; otherwise a dot keeps a little
        // way above it.
        bottom:
          inset.bottom === undefined
            ? Math.max(80, (inset.legend ?? 0) + LEGEND_GAP)
            : 80 + inset.bottom,
        left: side,
        right: side + (inset.right ?? 0),
        top: 88,
      },
    },
  }
}
