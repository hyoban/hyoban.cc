import mapboxgl, { type GeoJSONSource, type Map as MapboxMap } from 'mapbox-gl'
import type { Coordinates } from '../geo.ts'
import type { IconName } from '../icons.ts'
import { createElement, createIcon, setIcon } from './dom.ts'
import { lineFeature, type TravelMode } from './route.ts'

// The trip's map layers: the planned route (dashed), the drawn route, the piece being
// drawn right now, the stop dots and a car, ship or plane riding the route's tip.
//
// Nothing here sends the map new data every frame: each update goes through the map's
// worker and makes it lay out its labels again, which on a slow device costs a third
// of the frame rate. The piece being drawn is sent once and revealed by trimming it,
// and the head is an element over the map, moved in the frame the map draws.

const PLANNED_ID = 'trip-map-planned'
const ROUTE_ID = 'trip-map-route'
const ACTIVE_ROUTE_ID = 'trip-map-route-active'
const STOPS_ID = 'trip-map-stops'
// Blank symbols over the stop dots: circles don't take part in label placement, so
// without these the map's own place names print right over the dots.
const STOPS_CLEAR_ID = 'trip-map-stops-clear'
const CLEAR_SIZE = 20
const LAYER_IDS = [STOPS_CLEAR_ID, STOPS_ID, ACTIVE_ROUTE_ID, ROUTE_ID, PLANNED_ID]

const TRAVEL_ICONS: Record<TravelMode, IconName> = {
  car: 'car-front',
  ferry: 'ship',
  plane: 'plane',
  train: 'train-front',
}

export type StopState = 'current' | 'upcoming' | 'visited'

type StopDot = { coordinates: Coordinates; state: StopState; waypoint: boolean }

/** `ink` draws the route; `paper` fills what is still ahead. Both follow the theme. */
type Colors = { ink: string; paper: string }

/** A stretch being drawn: its whole line, and how far along it the tip is. */
type RoutePiece = {
  line: readonly Coordinates[]
  mode: TravelMode
  /** The segment the tip is on, and how far along it. */
  ratio: number
  segment: number
}

/** `headLabel` rides beside the car, ship or plane. */
export function createRouteLayers(
  getMap: () => MapboxMap | undefined,
  getColors: () => Colors,
  headLabel?: HTMLElement,
) {
  // What the sources hold now, so frames that don't change them skip the update.
  let shown: {
    line?: readonly Coordinates[] | undefined
    pointCount: number
    source?: GeoJSONSource
  } = { pointCount: -1 }
  // The piece's length along each segment, as the map measures it for trimming.
  let measured: { cumulative: number[]; line: readonly Coordinates[]; total: number } | undefined
  let head:
    | {
        coordinates?: Coordinates | undefined
        element: HTMLElement
        map: MapboxMap
        place: () => void
      }
    | undefined

  function getSource(id: string) {
    return getMap()?.getSource(id) as GeoJSONSource | undefined
  }

  /** Adds the layers unless they're there already; returns whether it added them. */
  function add(plannedLine: readonly Coordinates[]) {
    const map = getMap()

    if (!map || map.getSource(ROUTE_ID)) {
      return false
    }

    const { ink, paper } = getColors()
    const emptyCollection = { features: [], type: 'FeatureCollection' as const }
    const solidLine = {
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      // Unlit, the same colour by day and by night.
      paint: { 'line-color': ink, 'line-emissive-strength': 1, 'line-width': 3 },
      type: 'line',
    } as const

    map.addSource(PLANNED_ID, { data: lineFeature(plannedLine), type: 'geojson' })
    map.addSource(ROUTE_ID, { data: lineFeature([]), type: 'geojson' })
    // Line metrics let the layer be trimmed to how far the tip has come.
    map.addSource(ACTIVE_ROUTE_ID, { data: lineFeature([]), lineMetrics: true, type: 'geojson' })
    map.addSource(STOPS_ID, { data: emptyCollection, type: 'geojson' })
    map.addLayer({
      id: PLANNED_ID,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': ink,
        'line-dasharray': [1, 2],
        'line-emissive-strength': 1,
        'line-opacity': 0.3,
        'line-width': 2,
      },
      source: PLANNED_ID,
      type: 'line',
    })
    map.addLayer({ ...solidLine, id: ROUTE_ID, source: ROUTE_ID })
    map.addLayer({
      ...solidLine,
      id: ACTIVE_ROUTE_ID,
      paint: { ...solidLine.paint, 'line-trim-offset': [0, 1] },
      source: ACTIVE_ROUTE_ID,
    })
    map.addLayer({
      id: STOPS_ID,
      paint: {
        'circle-color': ['match', ['get', 'state'], 'upcoming', paper, ink],
        'circle-emissive-strength': 1,
        'circle-radius': [
          'match',
          ['get', 'state'],
          'current',
          7,
          ['case', ['get', 'waypoint'], 3, 4.5],
        ],
        'circle-stroke-color': ['match', ['get', 'state'], 'upcoming', ink, paper],
        'circle-stroke-width': 2,
      },
      source: STOPS_ID,
      type: 'circle',
    })

    if (!map.hasImage(STOPS_CLEAR_ID)) {
      map.addImage(STOPS_CLEAR_ID, {
        data: new Uint8Array(CLEAR_SIZE * CLEAR_SIZE * 4),
        height: CLEAR_SIZE,
        width: CLEAR_SIZE,
      })
    }

    map.addLayer({
      id: STOPS_CLEAR_ID,
      layout: {
        'icon-allow-overlap': true,
        'icon-image': STOPS_CLEAR_ID,
        'icon-size': [
          'match',
          ['get', 'state'],
          'current',
          1,
          ['case', ['get', 'waypoint'], 0.5, 0.7],
        ],
      },
      source: STOPS_ID,
      type: 'symbol',
    })
    shown = { pointCount: -1 }
    measured = undefined
    addHead(map)
    return true
  }

  function addHead(map: MapboxMap) {
    if (head?.map === map && head.element.isConnected) {
      return
    }

    removeHead()

    const element = createElement('div', 'trip-map-head', '')
    const place = () => {
      const coordinates = head?.coordinates

      if (coordinates) {
        const { x, y } = map.project([coordinates[0], coordinates[1]])
        element.style.transform = `translate3d(${x}px, ${y}px, 0)`
      }
    }

    element.setAttribute('aria-hidden', 'true')
    element.hidden = true
    element.append(createIcon(TRAVEL_ICONS.car), ...(headLabel ? [headLabel] : []))
    map.getContainer().append(element)
    // Sub-pixel transforms set as the map draws keep it on the route as the map glides.
    map.on('render', place)
    head = { element, map, place }
  }

  function removeHead() {
    if (head) {
      head.map.off('render', head.place)
      head.element.remove()
      head = undefined
    }
  }

  function remove() {
    const map = getMap()

    removeHead()

    for (const id of map ? LAYER_IDS : []) {
      if (map!.getLayer(id)) {
        map!.removeLayer(id)
      }

      if (map!.getSource(id)) {
        map!.removeSource(id)
      }
    }
  }

  /**
   * Shows the route through its first `pointCount` places, then `partial` (the piece
   * being drawn from the last of those) with the head at its tip.
   */
  function setRoute(pointCount: number, getLine: () => readonly Coordinates[], piece?: RoutePiece) {
    const source = getSource(ROUTE_ID)
    const activeSource = getSource(ACTIVE_ROUTE_ID)

    if (!source || !activeSource) {
      return
    }

    // The whole route is thousands of points; resend it only when it gains a place.
    if (source !== shown.source || pointCount !== shown.pointCount) {
      source.setData(lineFeature(getLine()))
      shown.source = source
      shown.pointCount = pointCount
    }

    // The piece is sent once; each frame only moves where its trim starts.
    if (piece?.line !== shown.line) {
      activeSource.setData(lineFeature(piece?.line ?? []))
      shown.line = piece?.line
    }

    if (piece) {
      getMap()?.setPaintProperty(ACTIVE_ROUTE_ID, 'line-trim-offset', [trimStart(piece), 1])
    }

    setHead(piece && tipOf(piece), piece?.mode ?? 'car')
  }

  /** How far along the piece its tip is, as a share of the line the map measures. */
  function trimStart({ line, ratio, segment }: RoutePiece) {
    if (measured?.line !== line) {
      // The map measures lines in its flat projection, not in degrees.
      const projected = line.map(([longitude, latitude]) =>
        mapboxgl.MercatorCoordinate.fromLngLat([longitude, latitude]),
      )
      const cumulative = [0]

      for (let index = 1; index < projected.length; index += 1) {
        const from = projected[index - 1]!
        const to = projected[index]!
        cumulative.push(cumulative[index - 1]! + Math.hypot(to.x - from.x, to.y - from.y))
      }

      measured = { cumulative, line, total: cumulative.at(-1) ?? 0 }
    }

    const { cumulative, total } = measured
    const start = cumulative[segment] ?? total
    const end = cumulative[segment + 1] ?? start

    return total > 0 ? Math.min((start + (end - start) * ratio) / total, 1) : 1
  }

  function setHead(coordinates: Coordinates | undefined, mode: TravelMode) {
    if (!head) {
      return
    }

    const icon = head.element.firstElementChild

    if (icon instanceof SVGSVGElement) {
      setIcon(icon, TRAVEL_ICONS[mode])
    }

    head.element.hidden = !coordinates
    head.coordinates = coordinates
    head.place()
  }

  function setStops(dots: readonly StopDot[]) {
    getSource(STOPS_ID)?.setData({
      features: dots.map(({ coordinates, state, waypoint }) => ({
        geometry: { coordinates: [coordinates[0], coordinates[1]], type: 'Point' },
        properties: { state, waypoint },
        type: 'Feature',
      })),
      type: 'FeatureCollection',
    })
  }

  return { add, remove, setRoute, setStops }
}

function tipOf({ line, ratio, segment }: RoutePiece): Coordinates {
  const start = line[segment]!
  const end = line[segment + 1] ?? start

  return [start[0] + (end[0] - start[0]) * ratio, start[1] + (end[1] - start[1]) * ratio]
}
