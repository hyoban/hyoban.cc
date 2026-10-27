import mapboxgl, { type Map as MapboxMap, type PaddingOptions } from 'mapbox-gl'
import { getHeading, getTurn, type Coordinates } from '../geo.ts'
import { getFramingPitch, getMapPitch } from '../map/basemap.ts'

// One calm camera for the whole playback. While the route is drawn, the camera keeps
// the line's moving tip in the middle, so it glides along with it: the tip eases in
// and out of every place, and so does the camera, with no separate move between
// cards. The zoom is set by the stretch being driven, so a long drive is seen from
// far enough out that the map never races past, and it changes slowly.
//
// The camera leans back as far as the map does at the zoom it's headed for, at the
// zoom's pace: the closer, the further.
//
// Flying over, the camera following the tip comes lower and leans further back, turned
// the way the drive goes, so the mountains stand up ahead of it; it stays out as far as
// Standard's terrain still rises (it flattens past zoom 12). It faces from where each
// drive sets off to where it ends, once for the drive, and turns slowly: following
// every bend of the road would swing the whole map about, which makes people dizzy.
// The views of the whole trip stay as they are, facing north.
//
// All run on critically damped springs: motion starts from rest, never overshoots,
// and a new target mid-move bends the motion instead of jolting it.

type Mercator = { x: number; y: number }

// Seconds to settle at 1× playback. The centre keeps up closely; the zoom drifts; the
// heading swings round slower still, at the same pace whatever the playback speed.
const CENTER_SETTLE = 0.8
const ZOOM_SETTLE = 2.6
const BEARING_SETTLE = 5
// A tip moving further than this in one frame jumped (a skip), rather than drove.
const JUMP_PIXELS = 160
// Flying over: this much closer than the drive would be framed, no closer than
// `FLYOVER_MAX_ZOOM`, leaning back this far, the zoom drifting slower still between
// drives. Coming closer makes the land rush past faster, and leaning further shows
// ever more of the map up to the horizon: both are harder to watch and have the map
// fetch and draw more tiles every second.
const FLYOVER_ZOOM_IN = 0.5
const FLYOVER_MAX_ZOOM = 12
const FLYOVER_PITCH = 54
const FLYOVER_ZOOM_SETTLE = 4
// A drive that doesn't get this far, as a share of the screen at its zoom, leaves the
// camera facing as it was.
const HEADING_SPAN = 0.15

export function createCamera({
  getFlyover = () => false,
  getFocus,
  getMap,
  getPadding,
  getSpeed,
  reducedMotion,
}: {
  /** Whether to fly over the route while following it. */
  getFlyover?: (() => boolean) | undefined
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
  let pitch: number | undefined
  let pitchVelocity = 0
  // Only while the camera has turned the map: left alone otherwise, as the viewer set it.
  let bearing: number | undefined
  let bearingVelocity = 0
  // Which way the drive being followed goes, in degrees from north.
  let heading: number | undefined
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

    const start = stretch[0]
    const end = stretch.at(-1)
    const map = getMap()

    if (start && end && map && target) {
      const from = toMercator(mapboxgl.LngLat.convert([...start]))
      const to = toMercator(mapboxgl.LngLat.convert([...end]))
      const span = Math.hypot(to.x - from.x, to.y - from.y) * 512 * 2 ** target.zoom
      const { clientHeight, clientWidth } = map.getCanvas()

      if (span > HEADING_SPAN * Math.min(clientWidth, clientHeight)) {
        heading = getHeading(start, end)
      }
    }
  }

  /**
   * Steps the camera. Call once per animation frame. Whether it moved the map: false
   * once it has settled.
   */
  function update(now: number) {
    const map = getMap()
    const seconds = lastTime === undefined ? 0 : Math.min((now - lastTime) / 1000, 0.1)

    lastTime = now

    if (!map || !shot || released) {
      return false
    }

    watch(map)

    const speed = Math.max(getSpeed(), 1)
    const centerOmega = (4 / CENTER_SETTLE) * speed
    const bearingOmega = 4 / BEARING_SETTLE
    const padding = getPadding()
    const goal = getTarget(map, shot, padding)
    const focus = trackFocus(seconds, map.getZoom())
    const goalCenter = goal?.center ?? lead(focus)

    if (!goal || !goalCenter) {
      return false
    }

    const flyover = shot.mode === 'follow' && getFlyover()
    const zoomOmega = (4 / (flyover ? FLYOVER_ZOOM_SETTLE : ZOOM_SETTLE)) * speed
    const goalZoom = flyover
      ? Math.max(goal.zoom, Math.min(goal.zoom + FLYOVER_ZOOM_IN, FLYOVER_MAX_ZOOM))
      : goal.zoom
    // Leaning as the map does at the zoom it's headed for, or far back flying over.
    const goalPitch = flyover ? FLYOVER_PITCH : getMapPitch(map, goalZoom)
    // Turned back north once flying over ends, then left alone.
    const turning = flyover || bearing !== undefined
    const goalBearing = flyover ? (heading ?? bearing ?? map.getBearing()) : 0

    if (!center || zoom === undefined || pitch === undefined || snap) {
      const current = snap ? goalCenter : toMercator(map.getCenter())

      center = { ...current }
      zoom = snap ? goalZoom : map.getZoom()
      pitch = snap ? goalPitch : map.getPitch()
      centerVelocity = { x: 0, y: 0 }
      zoomVelocity = 0
      pitchVelocity = 0
    }

    if (turning && (bearing === undefined || snap)) {
      bearing = snap ? goalBearing : map.getBearing()
      bearingVelocity = 0
    }

    if (snap) {
      snap = false
      apply(map, center, zoom, pitch, padding, bearing)
      return true
    }

    const x = spring(center.x, centerVelocity.x, goalCenter.x, centerOmega, seconds)
    const y = spring(center.y, centerVelocity.y, goalCenter.y, centerOmega, seconds)
    const z = spring(zoom, zoomVelocity, goalZoom, zoomOmega, seconds)
    const p = spring(pitch, pitchVelocity, goalPitch, zoomOmega, seconds)
    // The short way round.
    const b =
      bearing === undefined
        ? undefined
        : spring(
            bearing,
            bearingVelocity,
            bearing + getTurn(bearing, goalBearing),
            bearingOmega,
            seconds,
          )
    const pixel = 0.25 / (512 * 2 ** z.value)

    center = { x: x.value, y: y.value }
    centerVelocity = { x: x.velocity, y: y.velocity }
    zoom = z.value
    zoomVelocity = z.velocity
    pitch = p.value
    pitchVelocity = p.velocity

    if (b) {
      bearing = b.value
      bearingVelocity = b.velocity
    }

    const bearingSettled =
      !b ||
      (Math.abs(getTurn(b.value, goalBearing)) < 0.05 && Math.abs(b.velocity) * seconds < 0.05)

    // Settled to within a fraction of a pixel: leave the map be so it can go idle.
    if (
      Math.abs(x.value - goalCenter.x) < pixel &&
      Math.abs(y.value - goalCenter.y) < pixel &&
      Math.abs(z.value - goalZoom) < 0.002 &&
      Math.hypot(x.velocity, y.velocity) * seconds < pixel &&
      Math.abs(z.velocity) * seconds < 0.002 &&
      Math.abs(p.value - goalPitch) < 0.05 &&
      Math.abs(p.velocity) * seconds < 0.05 &&
      bearingSettled
    ) {
      if (
        Math.abs(map.getZoom() - goalZoom) < 0.002 &&
        Math.abs(map.getPitch() - goalPitch) < 0.05 &&
        (bearing === undefined || Math.abs(getTurn(map.getBearing(), goalBearing)) < 0.05)
      ) {
        if (!flyover) {
          bearing = undefined
        }

        return false
      }

      center = { ...goalCenter }
      zoom = goalZoom
      pitch = goalPitch

      if (bearing !== undefined) {
        bearing = goalBearing
      }
    }

    apply(map, center, zoom, pitch, padding, bearing)
    return true

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

    const camera = map.cameraForBounds(bounds, {
      maxZoom,
      padding,
      pitch: getFramingPitch(map, bounds, { maxZoom, padding }),
    })
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
    pitch = undefined
    bearing = undefined
    heading = undefined
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

function apply(
  map: MapboxMap,
  { x, y }: Mercator,
  zoom: number,
  pitch: number,
  padding: PaddingOptions,
  bearing: number | undefined,
) {
  map.jumpTo({
    center: new mapboxgl.MercatorCoordinate(x, y).toLngLat(),
    padding,
    pitch,
    zoom,
    ...(bearing !== undefined && { bearing }),
  })
}
