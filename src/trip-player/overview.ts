import mapboxgl, { type Map as MapboxMap } from 'mapbox-gl'
import type { TripSummary } from './types'

// Every trip's route on the overview map, as the way into its playback: pointing at a
// route lifts it above the others, and clicking it opens the trip. A finger has no
// pointing, so its first tap on a route lifts it and names it, and a second opens it.
//
// Opening a trip, the other routes fade away at once while the opened one stays a
// moment longer, so the eye follows it as the camera flies in and the trip's own route
// takes its place. Closing, the trip's route stays put while the player's layers go
// and relaxes into the others as they come back.

const PREFIX = 'calendar-trip-overview-'
// The second layer of a trip's route, its ferries and flights.
const STRAIGHT_SUFFIX = '-straight'
// How far apart, in degrees, the points added along a ferry or a flight are.
const DENSIFY_STEP = 0.25
// How far from a route, in pixels, a pointer or a finger still hits it.
const HIT_RADIUS = { mouse: 10, touch: 22 }
// Room kept around a route brought into view for a focused trip link.
const REVEAL_PADDING = 64
const BASE = { opacity: 0.5, width: 1.5 }
const DIMMED = { opacity: 0.15, width: 1.5 }
const LIFTED = { opacity: 1, width: 2.5 }
// The width the trip player draws its route at, so the two meet without a jump.
const PLAYING = { opacity: 1, width: 3 }
const HOVER = 160
const LEAVE = 280
const LINGER = { delay: 480, duration: 640 }
const RETURN = 640

type Look = { opacity: number; width: number }

export function createTripOverview({
  getInk,
  getMap,
  onSelect,
  reducedMotion,
  signal,
  trips,
}: {
  getInk: () => string
  getMap: () => MapboxMap | undefined
  onSelect: (id: string) => void
  reducedMotion: MediaQueryList
  signal: AbortSignal
  trips: readonly TripSummary[]
}) {
  // The trip being played; its route and the others are hidden meanwhile.
  let activeId: string | undefined
  let highlightedId: string | undefined
  let watched: MapboxMap | undefined
  // What last pressed or moved over the map. A tap also sends the mouse events of a
  // click, which mustn't count as pointing.
  let pointerType = 'mouse'
  // Cancels the second half of a two-step change, waiting on a timer or a frame.
  let settle: (() => void) | undefined
  // Styled like the trip's place labels (see index.css), above the pointer.
  const popup = new mapboxgl.Popup({
    anchor: 'bottom',
    className: 'calendar-map-trip-popup',
    closeButton: false,
    closeOnClick: false,
    focusAfterOpen: false,
    offset: 14,
  })
  const tripsById = new Map(trips.map((trip) => [trip.id, trip]))
  // Each trip's route as one line, to frame it and to find its middle.
  const lines = new Map(
    trips.map((trip) => [trip.id, trip.segments.flatMap((segment) => segment.line)]),
  )
  const tripIdsByLayer = new Map(
    trips.flatMap((trip) => [
      [`${PREFIX}${trip.id}`, trip.id],
      [`${PREFIX}${trip.id}${STRAIGHT_SUFFIX}`, trip.id],
    ]),
  )
  const layerIds = [...tripIdsByLayer.keys()]

  function getLook(id: string): Look {
    if (activeId) {
      return { opacity: 0, width: PLAYING.width }
    }

    if (!highlightedId) {
      return BASE
    }

    return id === highlightedId ? LIFTED : DIMMED
  }

  function paint(id: string, look: Look, transition: { delay?: number; duration: number }) {
    const map = getMap()
    const timing = {
      delay: 0,
      ...transition,
      duration: reducedMotion.matches ? 0 : transition.duration,
    }

    for (const layerId of [`${PREFIX}${id}`, `${PREFIX}${id}${STRAIGHT_SUFFIX}`]) {
      if (!map?.getLayer(layerId)) {
        continue
      }

      map.setPaintProperty(layerId, 'line-opacity-transition', timing)
      map.setPaintProperty(layerId, 'line-width-transition', timing)
      map.setPaintProperty(layerId, 'line-opacity', look.opacity)
      map.setPaintProperty(layerId, 'line-width', look.width)
    }
  }

  function paintAll(duration: number) {
    for (const trip of trips) {
      paint(trip.id, getLook(trip.id), { duration })
    }
  }

  /** Adds the routes unless they're there already, also after the style changes. */
  function add() {
    const map = getMap()

    if (!map || trips.length === 0 || map.getSource(`${PREFIX}${trips[0]!.id}`)) {
      return
    }

    // Under the map's own place names, so the routes don't strike through them.
    const beforeId = map.getStyle()?.layers?.find((layer) => layer.type === 'symbol')?.id

    // Oldest first, so the latest trip's route is drawn on top where routes cross.
    for (const trip of trips.toReversed()) {
      const id = `${PREFIX}${trip.id}`
      const look = getLook(trip.id)
      const paint = {
        'line-color': getInk(),
        'line-opacity': look.opacity,
        'line-width': look.width,
      }

      map.addSource(id, {
        data: {
          features: trip.segments.map((segment) => ({
            geometry: {
              coordinates: segment.straight ? densify(segment.line) : segment.line,
              type: 'LineString' as const,
            },
            properties: { straight: segment.straight },
            type: 'Feature' as const,
          })),
          type: 'FeatureCollection',
        },
        type: 'geojson',
      })
      map.addLayer(
        {
          filter: ['!', ['get', 'straight']],
          id,
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint,
          source: id,
          type: 'line',
        },
        beforeId,
      )
      map.addLayer(
        {
          filter: ['get', 'straight'],
          id: `${id}${STRAIGHT_SUFFIX}`,
          paint: { ...paint, 'line-dasharray': [2, 2] },
          source: id,
          type: 'line',
        },
        beforeId,
      )
    }

    watch(map)
  }

  function isTouch() {
    return pointerType !== 'mouse'
  }

  function getTripAt(point: mapboxgl.Point) {
    const map = getMap()

    if (activeId || !map) {
      return undefined
    }

    const layers = layerIds.filter((id) => map.getLayer(id))
    const radius = isTouch() ? HIT_RADIUS.touch : HIT_RADIUS.mouse
    const box: [mapboxgl.PointLike, mapboxgl.PointLike] = [
      [point.x - radius, point.y - radius],
      [point.x + radius, point.y + radius],
    ]
    // Topmost first, which is the latest trip where routes cross.
    const feature = layers.length > 0 ? map.queryRenderedFeatures(box, { layers })[0] : undefined
    const id = feature?.layer ? tripIdsByLayer.get(feature.layer.id) : undefined

    return id ? tripsById.get(id) : undefined
  }

  function watch(map: MapboxMap) {
    if (watched === map) {
      return
    }

    watched = map

    const notePointer = (event: PointerEvent) => {
      pointerType = event.pointerType
    }

    map.getCanvasContainer().addEventListener('pointerdown', notePointer, { capture: true, signal })
    map.getCanvasContainer().addEventListener('pointermove', notePointer, { capture: true, signal })
    map.on('mousemove', (event) => {
      if (isTouch()) {
        return
      }

      const trip = getTripAt(event.point)

      map.getCanvas().style.cursor = trip ? 'pointer' : ''
      highlight(trip?.id, event.lngLat)
    })
    map.on('mouseout', () => {
      if (!isTouch()) {
        highlight(undefined)
      }
    })
  }

  /** Lifts a route above the others, with its name by `at` or by the route's middle. */
  function highlight(id: string | undefined, at?: mapboxgl.LngLatLike) {
    const map = getMap()
    const trip = id ? tripsById.get(id) : undefined

    if (!trip || activeId || !map) {
      popup.remove()
    } else {
      const line = lines.get(trip.id)!
      const middle = line[Math.floor(line.length / 2)]!

      popup.setLngLat(at ?? middle)

      if (highlightedId !== id) {
        popup.setDOMContent(createPopupContent(trip, isTouch()))
      }

      if (!popup.isOpen()) {
        popup.addTo(map)
      }
    }

    const next = activeId ? undefined : trip?.id

    if (next !== highlightedId) {
      highlightedId = next
      paintAll(HOVER)
    }
  }

  /**
   * The trip under a click on the map, if any: opens it, or with a finger lifts it
   * first unless it already is. Reports whether the click was the trip's.
   */
  function click(point: mapboxgl.Point, at: mapboxgl.LngLat) {
    const trip = getTripAt(point)

    if (trip && (!isTouch() || highlightedId === trip.id)) {
      onSelect(trip.id)
    } else if (trip) {
      highlight(trip.id, at)
    } else if (isTouch() && highlightedId) {
      highlight(undefined)
    }

    return Boolean(trip)
  }

  /** Brings a trip's whole route into view, if part of it is off the map. */
  function reveal(id: string) {
    const map = getMap()
    const line = lines.get(id) ?? []

    if (!map || activeId || line.length === 0) {
      return
    }

    const view = map.getBounds()

    if (view && line.every((coordinates) => view.contains(coordinates))) {
      return
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const coordinates of line) {
      bounds.extend(coordinates)
    }

    map.fitBounds(bounds, {
      duration: reducedMotion.matches ? 0 : 500,
      maxZoom: map.getZoom(),
      padding: REVEAL_PADDING,
    })
  }

  /** Hides the routes as trip `id` opens, its own route staying a moment longer. */
  function hide(id: string) {
    if (activeId === id) {
      return
    }

    settle?.()
    activeId = id
    highlightedId = undefined
    popup.remove()

    const map = getMap()

    if (map) {
      map.getCanvas().style.cursor = ''
    }

    for (const trip of trips) {
      if (trip.id === id) {
        paint(trip.id, PLAYING, { duration: HOVER })
      } else {
        paint(trip.id, getLook(trip.id), { duration: LEAVE })
      }
    }

    const timer = setTimeout(() => {
      settle = undefined
      paint(id, getLook(id), { duration: LINGER.duration })
    }, LINGER.delay)

    settle = () => {
      clearTimeout(timer)
      settle = undefined
    }
  }

  /** Brings the routes back as the playing trip closes. */
  function show() {
    const previousId = activeId

    if (!previousId) {
      return
    }

    settle?.()
    activeId = undefined

    const map = getMap()

    // The trip's route takes the place of the player's at once; once that has been
    // drawn, it eases into the others as they fade in.
    paint(previousId, PLAYING, { duration: 0 })

    if (!map) {
      paintAll(RETURN)
      return
    }

    const frame = () => {
      settle = undefined
      paintAll(RETURN)
    }

    settle = () => {
      map.off('render', frame)
      settle = undefined
    }
    map.once('render', frame)
    map.triggerRepaint()
  }

  signal.addEventListener('abort', () => {
    settle?.()
    popup.remove()
  })

  return { add, click, hide, highlight, reveal, show }
}

/**
 * A straight leg with points every so often along it. On the globe, Mapbox stretches
 * the dashes of a long line with only its two ends until it looks solid.
 */
function densify(line: readonly [number, number][]) {
  const dense: [number, number][] = []

  for (const [index, [x2, y2]] of line.entries()) {
    const previous = line[index - 1]

    if (previous) {
      const [x1, y1] = previous
      const steps = Math.ceil(Math.hypot(x2 - x1, y2 - y1) / DENSIFY_STEP)

      for (let step = 1; step < steps; step += 1) {
        dense.push([x1 + ((x2 - x1) * step) / steps, y1 + ((y2 - y1) * step) / steps])
      }
    }

    dense.push([x2, y2])
  }

  return dense
}

function createPopupContent(trip: TripSummary, touch: boolean) {
  const content = document.createElement('div')
  const date = document.createElement('p')
  const title = document.createElement('p')

  date.className = 'text-[0.6875rem] text-tx-3 tabular-nums'
  date.textContent = trip.dateRange
  title.className = 'font-medium'
  title.textContent = trip.title
  content.append(date, title)

  if (touch) {
    content.append(
      Object.assign(document.createElement('p'), {
        className: 'text-[0.6875rem] text-tx-3',
        textContent: '再点一次回放',
      }),
    )
  }

  return content
}
