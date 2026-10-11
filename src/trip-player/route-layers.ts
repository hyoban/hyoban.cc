import type { GeoJSONSource, Map as MapboxMap } from 'mapbox-gl'
import type { Coordinates } from '../geo'
import { loadIconImage } from './dom'
import { lineFeature, type TravelMode } from './route'

// The trip's map layers: the planned route (dashed), the drawn route, the piece being
// drawn right now, the stop dots and a car, ship or plane riding the route's tip.

const PLANNED_ID = 'calendar-trip-planned'
const ROUTE_ID = 'calendar-trip-route'
// The piece of route being drawn right now, kept apart so each frame sends only it.
const ACTIVE_ROUTE_ID = 'calendar-trip-route-active'
const STOPS_ID = 'calendar-trip-stops'
// A map layer rather than a DOM marker, which Mapbox snaps to whole pixels and so
// steps when it moves slowly.
const HEAD_ID = 'calendar-trip-head'
const LAYER_IDS = [HEAD_ID, STOPS_ID, ACTIVE_ROUTE_ID, ROUTE_ID, PLANNED_ID]
const HEAD_SIZE = 26
const HEAD_ICON_SIZE = 14

const TRAVEL_ICONS: Record<TravelMode, string> = {
  car: 'i-lucide-car-front',
  ferry: 'i-lucide-ship',
  plane: 'i-lucide-plane',
}

export type StopState = 'current' | 'upcoming' | 'visited'

export type StopDot = { coordinates: Coordinates; state: StopState; waypoint: boolean }

/** `ink` draws the route; `paper` fills what is still ahead. Both follow the theme. */
type Colors = { ink: string; paper: string }

export function createRouteLayers(getMap: () => MapboxMap | undefined, getColors: () => Colors) {
  // What the sources hold now, so frames that don't change them skip the update.
  let shown: { head: boolean; partial: boolean; pointCount: number; source?: GeoJSONSource } = {
    head: false,
    partial: false,
    pointCount: -1,
  }

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
      paint: { 'line-color': ink, 'line-width': 3 },
      type: 'line',
    } as const

    map.addSource(PLANNED_ID, { data: lineFeature(plannedLine), type: 'geojson' })
    map.addSource(ROUTE_ID, { data: lineFeature([]), type: 'geojson' })
    map.addSource(ACTIVE_ROUTE_ID, { data: lineFeature([]), type: 'geojson' })
    map.addSource(HEAD_ID, { data: emptyCollection, type: 'geojson' })
    map.addSource(STOPS_ID, { data: emptyCollection, type: 'geojson' })
    map.addLayer({
      id: PLANNED_ID,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': ink, 'line-dasharray': [1, 2], 'line-opacity': 0.3, 'line-width': 2 },
      source: PLANNED_ID,
      type: 'line',
    })
    map.addLayer({ ...solidLine, id: ROUTE_ID, source: ROUTE_ID })
    map.addLayer({ ...solidLine, id: ACTIVE_ROUTE_ID, source: ACTIVE_ROUTE_ID })
    map.addLayer({
      id: STOPS_ID,
      paint: {
        'circle-color': ['match', ['get', 'state'], 'upcoming', paper, ink],
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
    map.addLayer({
      id: HEAD_ID,
      layout: {
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
        'icon-image': ['concat', `${HEAD_ID}-`, ['get', 'mode']],
      },
      source: HEAD_ID,
      type: 'symbol',
    })
    shown = { head: false, partial: false, pointCount: -1 }
    void addHeadImages(map, ink, paper)
    return true
  }

  function remove() {
    const map = getMap()

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
  function setRoute(
    pointCount: number,
    getLine: () => readonly Coordinates[],
    partial?: { from: Coordinates; mode: TravelMode; passed: readonly Coordinates[] },
  ) {
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

    if (partial?.passed.length) {
      activeSource.setData(lineFeature([partial.from, ...partial.passed]))
      shown.partial = true
    } else if (shown.partial) {
      activeSource.setData(lineFeature([]))
      shown.partial = false
    }

    setHead(partial?.passed.at(-1), partial?.mode ?? 'car')
  }

  function setHead(coordinates: Coordinates | undefined, mode: TravelMode) {
    const source = getSource(HEAD_ID)

    if (!source || (!coordinates && !shown.head)) {
      return
    }

    shown.head = Boolean(coordinates)
    source.setData({
      features: coordinates
        ? [
            {
              geometry: { coordinates: [coordinates[0], coordinates[1]], type: 'Point' },
              properties: { mode },
              type: 'Feature',
            },
          ]
        : [],
      type: 'FeatureCollection',
    })
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

/** Draws each travel mode's head, the icon on a disc in the route's colour. */
async function addHeadImages(map: MapboxMap, ink: string, paper: string) {
  const ratio = Math.max(window.devicePixelRatio || 1, 2)
  const size = HEAD_SIZE * ratio
  const iconSize = HEAD_ICON_SIZE * ratio

  await Promise.all(
    (Object.keys(TRAVEL_ICONS) as TravelMode[]).map(async (mode) => {
      const icon = await loadIconImage(TRAVEL_ICONS[mode])
      const canvas = document.createElement('canvas')
      const context = canvas.getContext('2d')

      if (!context || !icon) {
        return
      }

      canvas.width = size
      canvas.height = size
      context.drawImage(icon, (size - iconSize) / 2, (size - iconSize) / 2, iconSize, iconSize)
      context.globalCompositeOperation = 'source-in'
      context.fillStyle = paper
      context.fillRect(0, 0, size, size)
      context.globalCompositeOperation = 'destination-over'
      context.beginPath()
      context.arc(size / 2, size / 2, size / 2 - 2 * ratio, 0, Math.PI * 2)
      context.fillStyle = ink
      context.fill()
      context.lineWidth = 2 * ratio
      context.strokeStyle = paper
      context.stroke()

      const name = `${HEAD_ID}-${mode}`

      if (map.hasImage(name)) {
        map.removeImage(name)
      }

      map.addImage(name, context.getImageData(0, 0, size, size), { pixelRatio: ratio })
    }),
  )
}
