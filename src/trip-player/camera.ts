import mapboxgl, { type Map as MapboxMap, type PaddingOptions } from 'mapbox-gl'
import type { Coordinates } from '../geo'

// One calm camera for the whole playback. While the route is drawn, the camera keeps
// the line's moving tip in the middle, so it glides along with it: the tip eases in
// and out of every place, and so does the camera, with no separate move between
// cards. The zoom is set by the stretch being driven, so a long drive is seen from
// far enough out that the map never races past, and it changes slowly.
//
// Both run on critically damped springs: motion starts from rest, never overshoots,
// and a new target mid-move bends the motion instead of jolting it.

type Mercator = { x: number; y: number }

// Seconds to settle at 1× playback. The centre keeps up closely; the zoom drifts.
const CENTER_SETTLE = 0.8
const ZOOM_SETTLE = 2.6
// A tip moving further than this in one frame jumped (a skip), rather than drove.
const JUMP_PIXELS = 160

export function createCamera({
  getFocus,
  getMap,
  getPadding,
  getSpeed,
  reducedMotion,
}: {
  /** Where the camera should centre while following: the route's tip. */
  getFocus: () => Coordinates | undefined
  getMap: () => MapboxMap | undefined
  getPadding: () => PaddingOptions
  getSpeed: () => number
  reducedMotion: MediaQueryList
}) {
  // `overview` fits a set of places; `follow` centres on the focus at a zoom that
  // fits `stretch` around where it starts.
  let shot:
    | { coordinates: readonly Coordinates[]; maxZoom: number; mode: 'follow' | 'overview' }
    | undefined
  let target: { center?: Mercator; key: string; zoom: number } | undefined
  let center: Mercator | undefined
  let centerVelocity: Mercator = { x: 0, y: 0 }
  let zoom: number | undefined
  let zoomVelocity = 0
  let focusVelocity: Mercator = { x: 0, y: 0 }
  let lastFocus: Mercator | undefined
  let snap = false
  // The viewer moved the map themselves; the camera waits until the next shot.
  let released = false
  let lastTime: number | undefined
  let watched: MapboxMap | undefined

  function release(event: object) {
    // Only gestures; the camera's own moves have no originating event.
    if ('originalEvent' in event && event.originalEvent) {
      released = true
    }
  }

  function watch(map: MapboxMap) {
    if (watched === map) {
      return
    }

    map.on('dragstart', release)
    map.on('zoomstart', release)
    map.on('rotatestart', release)
    map.on('pitchstart', release)
    watched = map
  }

  function setShot(next: NonNullable<typeof shot>, instant = false) {
    shot = next
    target = undefined

    if (released) {
      // Picks up from wherever the viewer left the map.
      center = undefined
      released = false
    }

    snap = instant || reducedMotion.matches
    update(performance.now())
  }

  /** Shows all of `coordinates`, for the start and end of a trip. */
  function overview(
    coordinates: readonly Coordinates[],
    maxZoom: number,
    options: { instant?: boolean } = {},
  ) {
    setShot({ coordinates, maxZoom, mode: 'overview' }, options.instant)
  }

  /**
   * Follows the route's tip at a zoom that fits `stretch` (the drive about to be
   * drawn) around where it starts, so its far end is in view from the beginning.
   */
  function follow(stretch: readonly Coordinates[], maxZoom: number) {
    setShot({ coordinates: stretch, maxZoom, mode: 'follow' })
  }

  /** Steps the camera. Call once per animation frame. */
  function update(now: number) {
    const map = getMap()
    const seconds = lastTime === undefined ? 0 : Math.min((now - lastTime) / 1000, 0.1)

    lastTime = now

    if (!map || !shot || released) {
      return
    }

    watch(map)

    const speed = Math.max(getSpeed(), 1)
    const centerOmega = (4 / CENTER_SETTLE) * speed
    const zoomOmega = (4 / ZOOM_SETTLE) * speed
    const padding = getPadding()
    const goal = getTarget(map, shot, padding)
    const focus = trackFocus(seconds, map.getZoom())
    const goalCenter = goal?.center ?? lead(focus)

    if (!goal || !goalCenter) {
      return
    }

    if (!center || zoom === undefined || snap) {
      const current = snap ? goalCenter : toMercator(map.getCenter())

      center = { ...current }
      zoom = snap ? goal.zoom : map.getZoom()
      centerVelocity = { x: 0, y: 0 }
      zoomVelocity = 0
    }

    if (snap) {
      snap = false
      apply(map, center, zoom, padding)
      return
    }

    const x = spring(center.x, centerVelocity.x, goalCenter.x, centerOmega, seconds)
    const y = spring(center.y, centerVelocity.y, goalCenter.y, centerOmega, seconds)
    const z = spring(zoom, zoomVelocity, goal.zoom, zoomOmega, seconds)
    const pixel = 0.25 / (512 * 2 ** z.value)

    center = { x: x.value, y: y.value }
    centerVelocity = { x: x.velocity, y: y.velocity }
    zoom = z.value
    zoomVelocity = z.velocity

    // Settled to within a fraction of a pixel: leave the map be so it can go idle.
    if (
      Math.abs(x.value - goalCenter.x) < pixel &&
      Math.abs(y.value - goalCenter.y) < pixel &&
      Math.abs(z.value - goal.zoom) < 0.002 &&
      Math.hypot(x.velocity, y.velocity) * seconds < pixel &&
      Math.abs(z.velocity) * seconds < 0.002
    ) {
      if (Math.abs(map.getZoom() - goal.zoom) < 0.002) {
        return
      }

      center = { ...goalCenter }
      zoom = goal.zoom
    }

    apply(map, center, zoom, padding)

    /**
     * Aims ahead of the tip by the distance the spring would trail it at its current
     * speed, so the camera keeps the tip centred instead of lagging behind it.
     */
    function lead(point: Mercator | undefined) {
      return (
        point && {
          x: point.x + (focusVelocity.x * 2) / centerOmega,
          y: point.y + (focusVelocity.y * 2) / centerOmega,
        }
      )
    }
  }

  /** The tip's position, and a smoothed estimate of how fast it is moving. */
  function trackFocus(seconds: number, currentZoom: number) {
    const coordinates = getFocus()
    const focus = coordinates && toMercator(mapboxgl.LngLat.convert([...coordinates]))

    if (!focus || !lastFocus || seconds === 0) {
      lastFocus = focus
      focusVelocity = { x: 0, y: 0 }
      return focus
    }

    const moved = Math.hypot(focus.x - lastFocus.x, focus.y - lastFocus.y) * 512 * 2 ** currentZoom
    const smoothing = 1 - Math.exp(-seconds / 0.15)
    const velocity =
      moved > JUMP_PIXELS
        ? { x: 0, y: 0 }
        : { x: (focus.x - lastFocus.x) / seconds, y: (focus.y - lastFocus.y) / seconds }

    focusVelocity = {
      x: focusVelocity.x + (velocity.x - focusVelocity.x) * smoothing,
      y: focusVelocity.y + (velocity.y - focusVelocity.y) * smoothing,
    }
    lastFocus = focus
    return focus
  }

  /** The zoom (and for an overview the centre), worked out once per shot and layout. */
  function getTarget(map: MapboxMap, current: NonNullable<typeof shot>, padding: PaddingOptions) {
    const canvas = map.getCanvas()
    const key = `${canvas.clientWidth}x${canvas.clientHeight}:${JSON.stringify(padding)}`

    if (target?.key === key) {
      return target
    }

    const { coordinates, maxZoom, mode } = current
    const [start] = coordinates

    if (!start) {
      target = { key, zoom: map.getZoom() }
      return target
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const [longitude, latitude] of coordinates) {
      bounds.extend([longitude, latitude])

      // Following starts with the camera centred on the stretch's start, so it has
      // to see as far on the other side as well.
      if (mode === 'follow') {
        bounds.extend([2 * start[0] - longitude, 2 * start[1] - latitude])
      }
    }

    const camera = map.cameraForBounds(bounds, { maxZoom, padding })
    const zoom = camera?.zoom ?? maxZoom

    target = {
      key,
      zoom,
      ...(mode === 'overview' && camera?.center
        ? { center: toMercator(mapboxgl.LngLat.convert(camera.center)) }
        : {}),
    }
    return target
  }

  /** Keeps the camera still for this frame; it carries on from there afterwards. */
  function hold(now: number) {
    lastTime = now
  }

  function stop() {
    shot = undefined
    target = undefined
    center = undefined
    zoom = undefined
    lastFocus = undefined
    lastTime = undefined

    if (watched) {
      watched.off('dragstart', release)
      watched.off('zoomstart', release)
      watched.off('rotatestart', release)
      watched.off('pitchstart', release)
      watched = undefined
    }
  }

  return { follow, hold, overview, stop, update }
}

/** One exact step of a critically damped spring toward `goal`, stable at any frame rate. */
function spring(value: number, velocity: number, goal: number, omega: number, seconds: number) {
  const offset = value - goal
  const decay = Math.exp(-omega * seconds)
  const drift = (velocity + omega * offset) * seconds

  return {
    value: goal + (offset + drift) * decay,
    velocity: (velocity - omega * drift) * decay,
  }
}

function toMercator(lngLat: mapboxgl.LngLat): Mercator {
  const { x, y } = mapboxgl.MercatorCoordinate.fromLngLat(lngLat)
  return { x, y }
}

function apply(map: MapboxMap, { x, y }: Mercator, zoom: number, padding: PaddingOptions) {
  map.jumpTo({ center: new mapboxgl.MercatorCoordinate(x, y).toLngLat(), padding, zoom })
}
