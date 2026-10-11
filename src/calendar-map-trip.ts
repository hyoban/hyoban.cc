import mapboxgl, { type GeoJSONSource } from 'mapbox-gl'
import { SITE_URL } from './consts'
import { buildSlides, getStopPointEnds, pickCovers, type TripSlide } from './trip-slides'

type TripPlace = {
  latitude: number
  longitude: number
  name: string
}

type TripWaypoint = TripPlace & { date: string; day: number; time?: string }

type TripStop = {
  /** The URL hash that opens the trip on this card. */
  anchor: string
  date: string
  day: number
  href: string
  images: TripImage[]
  name: string
  places: TripPlace[]
  text: string
  time?: string
  /** Places the map pauses at on the way here, without a card of their own. */
  waypoints: TripWaypoint[]
}

type TripPoint = {
  coordinates: [number, number]
  /** The trip day a waypoint was passed on. */
  day?: number
  /** `第 N 天 · date · time`, shown above a waypoint's name on the map. */
  eyebrow?: string
  name: string
  stopIndex: number
  time?: string
  waypoint: boolean
}

type MapLabel = {
  coordinates: [number, number]
  eyebrow?: string
  name: string
}

type TripImage = {
  alt: string
  height: number
  src: string
  srcset: string
  thumbnail: string
  video?: string
  width: number
}

type TripSummary = { id: string; pageTitle: string }

type TripData = {
  dateRange: string
  dayCount: number
  distanceKm: number
  /** Hours of driving; ferries and flights aren't counted. */
  drivingHours: number
  id: string
  /** Default background music source. */
  music?: string
  pageTitle: string
  photoCount: number
  placeCount: number
  /** Hand-picked photos for the intro and summary, best first. */
  covers: TripImage[]
  stops: TripStop[]
  title: string
  trailingWaypoints: TripWaypoint[]
  /** Road geometry between consecutive places; null legs are drawn straight. */
  legs: (readonly (readonly [number, number])[] | null)[]
  legModes: TravelMode[]
  /** Each day's weather where it starts, by day number. */
  weather: Record<number, { icon: string; label: string }>
}

type Slide = TripSlide<TripStop>

type TravelMode = 'car' | 'ferry' | 'plane'

const TRAVEL_ICONS: Record<TravelMode, string> = {
  car: 'i-lucide-car-front',
  ferry: 'i-lucide-ship',
  plane: 'i-lucide-plane',
}

type TripPlayerOptions = {
  getMap: () => mapboxgl.Map | undefined
  onClose: () => void
  page: HTMLElement
  signal: AbortSignal
}

const ROUTE_SOURCE_ID = 'calendar-trip-route'
// The piece of route being drawn right now, kept apart so each frame sends only it.
const ACTIVE_ROUTE_SOURCE_ID = 'calendar-trip-route-active'
// A car, ship or plane riding the tip of the route. It is a map layer rather than a
// DOM marker, which Mapbox snaps to whole pixels and so steps when moving slowly.
const HEAD_SOURCE_ID = 'calendar-trip-head'
const HEAD_SIZE = 26
const HEAD_ICON_SIZE = 14
const PLANNED_SOURCE_ID = 'calendar-trip-planned'
const STOP_SOURCE_ID = 'calendar-trip-stops'
const INTRO_DURATION = 3000
const DAY_DURATION = 2200
const STOP_DURATION = 5000
const WAYPOINT_PAUSE = 2000
const PHOTO_DURATION = 2000
// How far photos slide when the card moves to the next or previous one.
const PHOTO_SLIDE_DISTANCE = 20
// Roughly the time it takes to read one Chinese character.
const CHARACTER_DURATION = 120
const IMAGE_SIZES = '(max-width: 48rem) calc(100vw - 3.5rem), 20rem'
const LEG_DURATION = 2200
const STOP_ZOOM = 9
// Each stop is framed together with the trip's other places this close to it.
const NEARBY_RADIUS_KM = 30
const NEARBY_MAX_ZOOM = 13
// On a long leg the camera pulls back from here in a stop's time to show both ends.
const FOLLOW_START = 0.4
const FOLLOW_SHARE = 0.4
// How long the summary card stays in an exported video before it ends.
const EXPORT_SUMMARY_HOLD = 4000
// avc1 keeps the codec setup in the file header; QuickTime can't play avc3 MP4s.
const EXPORT_VIDEO_TYPES = [
  'video/mp4;codecs=avc1,mp4a.40.2',
  'video/mp4',
  'video/webm;codecs=vp9,opus',
  'video/webm',
]
// Background music fades in at the start and out as the summary card ends.
const MUSIC_FADE_IN = 1
const MUSIC_FADE_OUT = 2
const MUSIC_VOLUME = 0.8
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')

type ExportRatio = 'landscape' | 'portrait'
// The story layout either plays on screen or is recorded to a video file.
type StageMode = 'present' | 'record'

const MUSIC_PREFERENCE_KEY = 'calendar-trip-music'
const SPEED_PREFERENCE_KEY = 'calendar-trip-speed'
const SPEEDS = [1, 1.5, 2] as const

// Region Capture is Chromium-only and not in the DOM typings yet.
type CroppableTrack = MediaStreamTrack & { cropTo?: (target: unknown) => Promise<void> }
type CropTargetConstructor = { fromElement: (element: Element) => Promise<unknown> }

export function createTripPlayer({ getMap, onClose, page, signal }: TripPlayerOptions) {
  const tripTitles = new Map(
    parseJson<TripSummary[]>(page, '[data-calendar-map-trips]', []).map((item) => [
      item.id,
      item.pageTitle,
    ]),
  )
  const loadedTrips = new Map<string, TripData>()
  const tripRequests = new Map<string, Promise<TripData | undefined>>()
  const inlineTrip = parseJson<TripData | null>(page, '[data-calendar-map-trip-data]', null)
  // The trip waiting on its data; `activeId` reports it so the map stays in trip mode.
  let requestedId: string | undefined

  if (inlineTrip) {
    loadedTrips.set(inlineTrip.id, inlineTrip)
  }

  const panel = queryRequired<HTMLElement>(page, '[data-calendar-map-trip]')
  const progress = queryRequired<HTMLOListElement>(page, '[data-calendar-map-trip-progress]')
  // Names the slide a progress segment jumps to, under the hovered or focused segment.
  const progressTip = createElement(
    'div',
    'pointer-events-none absolute z-20 rounded-[0.625rem] border border-[color-mix(in_srgb,var(--color-border)_82%,transparent)] bg-[color-mix(in_srgb,var(--color-bg)_88%,transparent)] px-2.5 py-1 text-xs whitespace-nowrap text-tx shadow-[0_0.25rem_1.25rem_rgb(0_0_0/0.12)] backdrop-blur-[1rem] [&[hidden]]:hidden',
    '',
  )
  progressTip.hidden = true
  progressTip.setAttribute('aria-hidden', 'true')
  panel.append(progressTip)
  const header = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-header]')
  const eyebrow = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-eyebrow]')
  const title = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-title]')
  const body = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-body]')
  const previousButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-previous]')
  const nextButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-next]')
  const playButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-play]')
  const closeButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-close]')
  const stage = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage]')
  const stageHeader = queryRequired<HTMLElement>(page, '.calendar-map-trip-stage__header')
  const stageEyebrow = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage-eyebrow]')
  const stageTitle = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage-title]')
  const stagePhotos = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage-photos]')
  const stageCenter = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage-center]')
  const stageProgress = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage-progress]')
  let stagePhotoTimer: ReturnType<typeof setTimeout> | undefined
  let stagePhotosReady = false
  // The current stop's photo card: one per stop, with a frame that resizes per photo.
  let stageCard: { frame?: HTMLElement; frameWidth: number; photoMax: number } | undefined
  const controls = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-controls]')
  const actions = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-actions]')
  const exportButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-export]')
  const exportOptions = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-export-options]')
  const exportMusic = queryRequired<HTMLSelectElement>(
    page,
    '[data-calendar-map-trip-export-music]',
  )
  const presentButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-present]')
  const musicButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-music]')
  const speedButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-speed]')
  const announcer = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-announcer]')
  let staging:
    | {
        mode: StageMode
        music?: { context: AudioContext; gain: GainNode; source: AudioBufferSourceNode }
        recorder?: MediaRecorder
        stopTimer?: ReturnType<typeof setTimeout>
        stream?: MediaStream
      }
    | undefined
  let stageMode: StageMode = 'record'
  // Where each day starts in the video being recorded, for the description's chapters.
  let chapters: { label: string; time: number }[] = []
  let recordingStart: number | undefined
  // Music for the ordinary card playback, switched on with the music button.
  let ambient:
    | { context: AudioContext; gain: GainNode; source: AudioBufferSourceNode; src: string }
    | undefined
  let musicOn = readMusicPreference()
  let pendingAmbient: { src: string } | undefined
  let stageToken: object | undefined
  let stopPopups: mapboxgl.Popup[] = []
  let trip: TripData | undefined
  // Photos for the intro and summary.
  let covers: TripImage[] = []
  // Every place of every stop in visiting order; the route runs through these.
  let points: TripPoint[] = []
  // Index into `points` just past each stop's last place.
  let stopPointEnds: number[] = []
  let slides: Slide[] = []
  let index = 0
  let elapsed = 0
  let playing = false
  let speed: number = readSpeedPreference()
  // What the route sources hold now, so frames that don't change them skip the update.
  const shownRoute: {
    head: boolean
    partial: boolean
    pointCount: number
    source?: GeoJSONSource
  } = { head: false, partial: false, pointCount: -1 }
  let frame: number | undefined
  let lastFrameTime: number | undefined
  let legFrame: number | undefined
  let finishLeg: (() => void) | undefined
  // Bumped on every slide change so an unfinished trip between waypoints stops.
  let travelToken = 0
  let traveling = false
  let currentWaypointPoint = -1
  let drawnPointCount = 0
  // The stop whose long leg the camera has already pulled back for.
  let followedStop = -1
  let routeProgress:
    | { lengths: number[]; line: [number, number][]; stopIndex: number; total: number }
    | undefined
  let photoIndex = 0
  let photo: HTMLImageElement | undefined
  let thumbnails: HTMLButtonElement[] = []
  let photoAnimation: Animation | undefined
  let liveVideo: HTMLVideoElement | undefined
  // A mouse resting on the card holds the current slide so it can be read.
  let hovering = false

  function getSlideDuration(slide: Slide | undefined) {
    if (slide?.type === 'intro') {
      return INTRO_DURATION
    }

    if (slide?.type === 'day') {
      return DAY_DURATION
    }

    return slide?.type === 'stop'
      ? Math.max(
          STOP_DURATION,
          slide.stop.images.length * PHOTO_DURATION,
          slide.stop.text.length * CHARACTER_DURATION,
        )
      : undefined
  }

  // The page itself becomes a narrow stage while recording or presenting, so measure it.
  function isNarrow() {
    return page.clientWidth <= 768
  }

  /** While recording, a story-style overlay replaces the side card. */
  function isStageLayout() {
    return page.dataset.exportRatio !== undefined
  }

  function getCameraPadding() {
    if (isStageLayout()) {
      const pageRect = page.getBoundingClientRect()
      // Room for the header plus a two-line label above the topmost place.
      const top = stageHeader.getBoundingClientRect().bottom - pageRect.top + 72
      const photos = stagePhotos.getBoundingClientRect()

      return page.dataset.exportRatio === 'landscape'
        ? { bottom: 64, left: 96, right: pageRect.right - photos.left + 96, top }
        : { bottom: pageRect.bottom - photos.top + 16, left: 72, right: 72, top }
    }

    if (isNarrow()) {
      // Leaves room for the one-row toolbar, which returns when playback pauses.
      return { bottom: panel.offsetHeight + 40, left: 40, right: 40, top: 72 }
    }

    // The toolbar is hidden during playback, so the top needs little room.
    return { bottom: 64, left: panel.offsetWidth + 64, right: 64, top: 64 }
  }

  function getStopPointStart(stopIndex: number) {
    return stopIndex === 0 ? 0 : stopPointEnds[stopIndex - 1]!
  }

  /**
   * When the next point is far away, eases the camera out to frame the stop and that
   * point together, so the moving route stays in view on its way there.
   */
  function followLongLeg(stop: TripStop, next: TripPoint, duration: number) {
    const map = getMap()
    const places = stop.places.map((place) => [place.longitude, place.latitude] as [number, number])

    if (
      !map ||
      places.some((coordinates) => getDistanceKm(coordinates, next.coordinates) <= NEARBY_RADIUS_KM)
    ) {
      return
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const coordinates of [...places, next.coordinates]) {
      bounds.extend(coordinates)
    }

    const camera = map.cameraForBounds(bounds, {
      maxZoom: NEARBY_MAX_ZOOM,
      padding: getCameraPadding(),
    })

    if (!camera) {
      return
    }

    map.easeTo({
      ...camera,
      duration: reducedMotion.matches ? 0 : (duration * FOLLOW_SHARE) / speed,
      easing: (t) => t * (2 - t),
      essential: false,
      padding: getCameraPadding(),
    })
  }

  /** Index in `points` of a stop's first place, after the waypoints leading to it. */
  function getStopPlaceStart(stopIndex: number) {
    return getStopPointStart(stopIndex) + (trip?.stops[stopIndex]?.waypoints.length ?? 0)
  }

  /**
   * While a stop's card is up, the route keeps moving: from the stop's first place,
   * through its other places, on to the next point, arriving as the card's time ends.
   */
  function renderRouteProgress() {
    const slide = slides[index]
    const duration = getSlideDuration(slide)

    if (slide?.type !== 'stop' || !duration || traveling || legFrame !== undefined) {
      return
    }

    const from = getStopPlaceStart(slide.stopIndex)
    const to = Math.min(stopPointEnds[slide.stopIndex]!, points.length - 1)

    if (routeProgress?.stopIndex !== slide.stopIndex) {
      const line = getRouteLine(from, to + 1)
      const lengths = line
        .slice(1)
        .map((coordinates, segmentIndex) => getPlanarDistance(line[segmentIndex]!, coordinates))

      routeProgress = {
        lengths,
        line,
        stopIndex: slide.stopIndex,
        total: lengths.reduce((total, length) => total + length, 0),
      }
    }

    const fraction = Math.min(elapsed / duration, 1)

    if (fraction >= FOLLOW_START && followedStop !== slide.stopIndex) {
      followedStop = slide.stopIndex
      followLongLeg(slide.stop, points[to]!, duration)
    }

    if (fraction >= 1 || routeProgress.total === 0) {
      drawnPointCount = fraction >= 1 ? to + 1 : from + 1
      setRoute(drawnPointCount)
      return
    }

    const { lengths, line } = routeProgress
    let remaining = fraction * routeProgress.total
    let segmentIndex = 0

    while (segmentIndex < lengths.length - 1 && remaining > lengths[segmentIndex]!) {
      remaining -= lengths[segmentIndex]!
      segmentIndex += 1
    }

    const start = line[segmentIndex]!
    const end = line[segmentIndex + 1]!
    const ratio = lengths[segmentIndex] ? remaining / lengths[segmentIndex]! : 1

    drawnPointCount = from + 1
    setRoute(
      from + 1,
      [
        ...line.slice(1, segmentIndex + 1),
        [start[0] + (end[0] - start[0]) * ratio, start[1] + (end[1] - start[1]) * ratio],
      ],
      getTravelMode(start, end),
    )
  }

  function getLineColor() {
    return getComputedStyle(page).getPropertyValue('--color-tx').trim() || '#21201c'
  }

  function getBackgroundColor() {
    return getComputedStyle(page).getPropertyValue('--color-bg').trim() || '#fdfdfc'
  }

  function addLayers() {
    const map = getMap()

    if (!map || !trip || map.getSource(ROUTE_SOURCE_ID)) {
      return
    }

    const lineColor = getLineColor()
    const emptyLine = {
      geometry: { coordinates: [], type: 'LineString' as const },
      properties: {},
      type: 'Feature' as const,
    }

    map.addSource(PLANNED_SOURCE_ID, { data: emptyLine, type: 'geojson' })
    map.addSource(ROUTE_SOURCE_ID, { data: emptyLine, type: 'geojson' })
    map.addSource(ACTIVE_ROUTE_SOURCE_ID, { data: emptyLine, type: 'geojson' })
    map.addSource(HEAD_SOURCE_ID, {
      data: { features: [], type: 'FeatureCollection' },
      type: 'geojson',
    })
    map.addSource(STOP_SOURCE_ID, {
      data: { features: [], type: 'FeatureCollection' },
      type: 'geojson',
    })
    map.addLayer({
      id: PLANNED_SOURCE_ID,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': lineColor,
        'line-dasharray': [1, 2],
        'line-opacity': 0.3,
        'line-width': 2,
      },
      source: PLANNED_SOURCE_ID,
      type: 'line',
    })
    map.addLayer({
      id: ROUTE_SOURCE_ID,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': lineColor, 'line-width': 3 },
      source: ROUTE_SOURCE_ID,
      type: 'line',
    })
    map.addLayer({
      id: ACTIVE_ROUTE_SOURCE_ID,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': lineColor, 'line-width': 3 },
      source: ACTIVE_ROUTE_SOURCE_ID,
      type: 'line',
    })
    map.addLayer({
      id: STOP_SOURCE_ID,
      paint: {
        'circle-color': ['match', ['get', 'state'], 'upcoming', getBackgroundColor(), lineColor],
        'circle-radius': [
          'match',
          ['get', 'state'],
          'current',
          7,
          ['case', ['get', 'waypoint'], 3, 4.5],
        ],
        'circle-stroke-color': [
          'match',
          ['get', 'state'],
          'upcoming',
          lineColor,
          getBackgroundColor(),
        ],
        'circle-stroke-width': 2,
      },
      source: STOP_SOURCE_ID,
      type: 'circle',
    })
    map.addLayer({
      id: HEAD_SOURCE_ID,
      layout: {
        'icon-allow-overlap': true,
        'icon-ignore-placement': true,
        'icon-image': ['concat', `${HEAD_SOURCE_ID}-`, ['get', 'mode']],
      },
      source: HEAD_SOURCE_ID,
      type: 'symbol',
    })
    void addHeadImages(map)

    ;(map.getSource(PLANNED_SOURCE_ID) as GeoJSONSource).setData(
      lineFeature(getRouteLine(0, points.length)),
    )
    setRoute(drawnPointCount)
    updateStops()
  }

  function removeLayers() {
    const map = getMap()

    if (!map) {
      return
    }

    for (const id of [
      HEAD_SOURCE_ID,
      STOP_SOURCE_ID,
      ACTIVE_ROUTE_SOURCE_ID,
      ROUTE_SOURCE_ID,
      PLANNED_SOURCE_ID,
    ]) {
      if (map.getLayer(id)) {
        map.removeLayer(id)
      }

      if (map.getSource(id)) {
        map.removeSource(id)
      }
    }
  }

  /** The line from `points[from]` to `points[to - 1]`, along roads where a route is known. */
  function getRouteLine(from: number, to: number) {
    const line: [number, number][] = []

    for (let index = from; index < to; index += 1) {
      const point = points[index]!

      if (index > from) {
        const road = trip?.legs[index - 1]

        if (road) {
          line.push(
            ...road.map((coordinates) => [coordinates[0], coordinates[1]] as [number, number]),
          )
        }
      }

      line.push(point.coordinates)
    }

    return line
  }

  function setRoute(pointCount: number, partial?: [number, number][], mode: TravelMode = 'car') {
    const map = getMap()
    const source = map?.getSource(ROUTE_SOURCE_ID) as GeoJSONSource | undefined
    const activeSource = map?.getSource(ACTIVE_ROUTE_SOURCE_ID) as GeoJSONSource | undefined

    if (!trip || !source || !activeSource) {
      return
    }

    // The whole route is thousands of points; resend it only when it gains a place.
    if (source !== shownRoute.source || pointCount !== shownRoute.pointCount) {
      source.setData(lineFeature(getRouteLine(0, pointCount)))
      shownRoute.source = source
      shownRoute.pointCount = pointCount
    }

    const start = points[pointCount - 1]

    if (partial?.length && start) {
      activeSource.setData(lineFeature([start.coordinates, ...partial]))
      shownRoute.partial = true
    } else if (shownRoute.partial) {
      activeSource.setData(lineFeature([]))
      shownRoute.partial = false
    }

    setTravelHead(partial?.at(-1), mode)
  }

  /** The ferry or plane leg whose straight line runs from `start` to `end`, else by car. */
  function getTravelMode(start: [number, number], end: [number, number]): TravelMode {
    const same = (first: [number, number], second: [number, number]) =>
      first[0] === second[0] && first[1] === second[1]
    const legIndex = trip?.legModes.findIndex(
      (mode, legIndex) =>
        mode !== 'car' &&
        same(points[legIndex]!.coordinates, start) &&
        same(points[legIndex + 1]!.coordinates, end),
    )

    return legIndex === undefined || legIndex === -1 ? 'car' : trip!.legModes[legIndex]!
  }

  /** Moves the head to the route's tip, or hides it when nothing is being drawn. */
  function setTravelHead(coordinates: [number, number] | undefined, mode: TravelMode) {
    const source = getMap()?.getSource(HEAD_SOURCE_ID) as GeoJSONSource | undefined

    if (!source || (!coordinates && !shownRoute.head)) {
      return
    }

    shownRoute.head = Boolean(coordinates)
    source.setData({
      features: coordinates
        ? [
            {
              geometry: { coordinates, type: 'Point' as const },
              properties: { mode },
              type: 'Feature' as const,
            },
          ]
        : [],
      type: 'FeatureCollection',
    })
  }

  /** Draws each travel mode's head (a dark disc with the icon) into the map's images. */
  async function addHeadImages(map: mapboxgl.Map) {
    const ratio = Math.max(window.devicePixelRatio || 1, 2)
    const fill = getLineColor()
    const ink = getBackgroundColor()

    await Promise.all(
      (Object.keys(TRAVEL_ICONS) as TravelMode[]).map(async (mode) => {
        const icon = await loadIconImage(TRAVEL_ICONS[mode])
        const canvas = document.createElement('canvas')
        const context = canvas.getContext('2d')
        const size = HEAD_SIZE * ratio
        const iconSize = HEAD_ICON_SIZE * ratio

        if (!context || !icon) {
          return
        }

        canvas.width = size
        canvas.height = size
        // The icon in the page background colour on a disc in the text colour.
        context.drawImage(icon, (size - iconSize) / 2, (size - iconSize) / 2, iconSize, iconSize)
        context.globalCompositeOperation = 'source-in'
        context.fillStyle = ink
        context.fillRect(0, 0, size, size)
        context.globalCompositeOperation = 'destination-over'
        context.beginPath()
        context.arc(size / 2, size / 2, size / 2 - 2 * ratio, 0, Math.PI * 2)
        context.fillStyle = fill
        context.fill()
        context.lineWidth = 2 * ratio
        context.strokeStyle = ink
        context.stroke()

        const name = `${HEAD_SOURCE_ID}-${mode}`

        if (map.hasImage(name)) {
          map.removeImage(name)
        }

        map.addImage(name, context.getImageData(0, 0, size, size), { pixelRatio: ratio })
      }),
    )
  }

  function updateStops() {
    const source = getMap()?.getSource(STOP_SOURCE_ID) as GeoJSONSource | undefined
    const slide = slides[index]

    if (!trip || !source) {
      return
    }

    const currentStopIndex = slide?.type === 'stop' ? slide.stopIndex : -1

    source.setData({
      features: points.map((point, pointIndex) => ({
        geometry: { coordinates: point.coordinates, type: 'Point' as const },
        properties: {
          waypoint: point.waypoint,
          state:
            pointIndex === currentWaypointPoint ||
            (point.stopIndex === currentStopIndex && !point.waypoint && !traveling)
              ? 'current'
              : pointIndex < drawnPointCount
                ? 'visited'
                : 'upcoming',
        },
        type: 'Feature' as const,
      })),
      type: 'FeatureCollection',
    })
  }

  function cancelLeg() {
    if (legFrame !== undefined) {
      cancelAnimationFrame(legFrame)
      legFrame = undefined
    }

    finishLeg?.()
    finishLeg = undefined
  }

  /** Draws the route from `points[legStart]` through every point before `legEnd`. */
  function drawLeg(legStart: number, legEnd: number) {
    cancelLeg()

    return new Promise<void>((resolve) => {
      finishLeg = resolve
      animateLeg(legStart, legEnd)
    })
  }

  function animateLeg(legStart: number, legEnd: number) {
    const leg = getRouteLine(legStart, legEnd)
    const segmentLengths = leg
      .slice(1)
      .map((coordinates, segmentIndex) => getPlanarDistance(leg[segmentIndex]!, coordinates))
    const legLength = segmentLengths.reduce((total, length) => total + length, 0)

    if (legLength === 0 || reducedMotion.matches) {
      drawnPointCount = legEnd
      setRoute(drawnPointCount)
      cancelLeg()
      return
    }

    drawnPointCount = legStart + 1
    const startTime = performance.now()
    const step = (now: number) => {
      const t = Math.min((now - startTime) / getLegDuration(), 1)

      if (t >= 1) {
        legFrame = undefined
        drawnPointCount = legEnd
        setRoute(drawnPointCount)
        updateStops()
        cancelLeg()
        return
      }

      let remaining = (1 - (1 - t) ** 3) * legLength
      let segmentIndex = 0

      while (
        segmentIndex < segmentLengths.length - 1 &&
        remaining > segmentLengths[segmentIndex]!
      ) {
        remaining -= segmentLengths[segmentIndex]!
        segmentIndex += 1
      }

      const from = leg[segmentIndex]!
      const to = leg[segmentIndex + 1]!
      const ratio = segmentLengths[segmentIndex] ? remaining / segmentLengths[segmentIndex]! : 1

      // The drawn part of this leg follows its road shape up to the moving tip.
      setRoute(
        legStart + 1,
        [
          ...leg.slice(1, segmentIndex + 1),
          [from[0] + (to[0] - from[0]) * ratio, from[1] + (to[1] - from[1]) * ratio],
        ],
        getTravelMode(from, to),
      )
      legFrame = requestAnimationFrame(step)
    }

    legFrame = requestAnimationFrame(step)
  }

  function fitTrip(options: { instant?: boolean } = {}) {
    const map = getMap()

    if (!map || !trip) {
      return
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const point of points) {
      bounds.extend(point.coordinates)
    }

    map.fitBounds(bounds, {
      duration: reducedMotion.matches || options.instant ? 0 : 1200,
      maxZoom: STOP_ZOOM,
      padding: getCameraPadding(),
    })
  }

  /** Frames every place of one day, as a preview before its stops play. */
  /** Keeps a long leg in view while it is drawn, from `points[from]` to `points[to]`. */
  function frameLeg(from: number, to: number) {
    const map = getMap()
    const bounds = new mapboxgl.LngLatBounds()

    for (const coordinates of getRouteLine(from, to + 1)) {
      bounds.extend(coordinates)
    }

    if (!map || bounds.isEmpty()) {
      return
    }

    removeStopPopups()
    map.fitBounds(bounds, {
      duration: reducedMotion.matches ? 0 : getLegDuration() / 2,
      maxZoom: NEARBY_MAX_ZOOM - 1,
      padding: getCameraPadding(),
    })
  }

  /** Frames where the day sets off from and every place it visits. */
  function fitDay({ day, stopIndex }: Extract<Slide, { type: 'day' }>) {
    const map = getMap()

    if (!map || !trip) {
      return
    }

    const bounds = new mapboxgl.LngLatBounds()
    // The last place before the day's own: where the night was spent, or the last
    // waypoint passed the evening before. Keeps the day's first drive in view.
    const setOff = points[getStopPointStart(stopIndex) + getEarlierDayWaypointCount(stopIndex) - 1]

    if (setOff) {
      bounds.extend(setOff.coordinates)
    }

    for (const stop of trip.stops) {
      if (stop.day === day) {
        for (const place of [
          ...stop.waypoints.filter((waypoint) => waypoint.day === day),
          ...stop.places,
        ]) {
          bounds.extend([place.longitude, place.latitude])
        }
      }
    }

    if (bounds.isEmpty()) {
      return
    }

    map.fitBounds(bounds, {
      duration: reducedMotion.matches ? 0 : 1200,
      maxZoom: NEARBY_MAX_ZOOM - 1,
      padding: getCameraPadding(),
    })
  }

  function moveCamera() {
    const map = getMap()
    const slide = slides[index]

    if (!map || !slide) {
      return
    }

    removeStopPopups()

    if (slide.type === 'day') {
      fitDay(slide)
      return
    }

    if (slide.type !== 'stop') {
      fitTrip()
      return
    }

    const { places } = slide.stop

    // The first place carries the day and time; the card itself shows no title. The
    // recording header already shows the day, so its labels only add the time.
    showPopups(
      places.map((place, placeIndex) =>
        isStageLayout()
          ? {
              coordinates: [place.longitude, place.latitude],
              name:
                placeIndex === 0 && slide.stop.time
                  ? `${place.name} · ${slide.stop.time}`
                  : place.name,
            }
          : {
              coordinates: [place.longitude, place.latitude],
              name: place.name,
              ...(placeIndex === 0 ? { eyebrow: getStopEyebrow(slide.stop) } : {}),
            },
      ),
    )

    const placeCoordinates = places.map(
      (place) => [place.longitude, place.latitude] as [number, number],
    )
    const bounds = new mapboxgl.LngLatBounds()

    for (const point of points) {
      if (
        placeCoordinates.some(
          (coordinates) => getDistanceKm(coordinates, point.coordinates) <= NEARBY_RADIUS_KM,
        )
      ) {
        bounds.extend(point.coordinates)
      }
    }

    const padding = getCameraPadding()
    const camera = bounds.isEmpty()
      ? undefined
      : map.cameraForBounds(bounds, { maxZoom: NEARBY_MAX_ZOOM, padding })
    const options = {
      center: camera?.center ?? placeCoordinates[0]!,
      padding,
      zoom:
        camera?.zoom !== undefined && bounds.getNorthEast().distanceTo(bounds.getSouthWest()) > 0
          ? camera.zoom
          : STOP_ZOOM,
    }

    if (reducedMotion.matches) {
      map.jumpTo(options)
    } else {
      map.flyTo({ ...options, duration: getLegDuration(), essential: false })
    }
  }

  function showPopups(labels: MapLabel[]) {
    const map = getMap()

    removeStopPopups()

    if (!map) {
      return
    }

    stopPopups = labels.map(({ coordinates, eyebrow: labelEyebrow, name }) => {
      const content = createElement('div', 'grid gap-0.5 whitespace-nowrap', '')

      if (labelEyebrow) {
        content.append(createElement('p', 'text-[0.6875rem] text-tx-3 tabular-nums', labelEyebrow))
      }

      content.append(createElement('p', 'text-sm font-medium', name))

      return new mapboxgl.Popup({
        // On wide screens the card sits on the left, so labels open to the right.
        anchor: isNarrow() || isStageLayout() ? 'bottom' : 'bottom-left',
        className: 'calendar-map-trip-label',
        closeButton: false,
        closeOnClick: false,
        focusAfterOpen: false,
        maxWidth: 'none',
        offset: 12,
      })
        .setLngLat(coordinates)
        .setDOMContent(content)
        .addTo(map)
    })
  }

  /**
   * Flies through `points[first..first + count)` one by one, drawing the route and
   * pausing at each, then calls `arrive`. Gives up when another slide takes over.
   */
  async function travelThroughWaypoints(first: number, count: number, arrive: () => void) {
    const token = travelToken
    const map = getMap()

    traveling = true

    if (isStageLayout()) {
      clearStagePhotos()
    }

    for (let pointIndex = first; pointIndex < first + count; pointIndex += 1) {
      const point = points[pointIndex]!

      currentWaypointPoint = pointIndex
      updateStops()
      showPopups([
        // The stage header names the day; a waypoint from another day says its own.
        isStageLayout() && point.day === getSlideDay(slides[index])
          ? {
              coordinates: point.coordinates,
              name: point.time ? `${point.name} · ${point.time}` : point.name,
            }
          : {
              coordinates: point.coordinates,
              name: point.name,
              ...(point.eyebrow ? { eyebrow: point.eyebrow } : {}),
            },
      ])
      map?.flyTo({
        center: point.coordinates,
        duration: getLegDuration(),
        essential: false,
        padding: getCameraPadding(),
        zoom: STOP_ZOOM,
      })
      // Wait for both the camera and the route to arrive, then pause there.
      await Promise.all([
        drawnPointCount >= pointIndex + 1
          ? Promise.resolve()
          : drawLeg(Math.max(pointIndex - 1, 0), pointIndex + 1),
        new Promise((resolve) => setTimeout(resolve, reducedMotion.matches ? 0 : getLegDuration())),
      ])
      await new Promise((resolve) => setTimeout(resolve, WAYPOINT_PAUSE / speed))

      if (token !== travelToken) {
        return
      }
    }

    traveling = false
    currentWaypointPoint = -1
    updateStops()
    arrive()
  }

  function removeStopPopups() {
    for (const popup of stopPopups) {
      popup.remove()
    }

    stopPopups = []
  }

  function showProgressTip(button: HTMLElement) {
    const label = button.getAttribute('aria-label')

    if (!label || panel.hidden) {
      return
    }

    progressTip.textContent = label
    progressTip.hidden = false

    const panelRect = panel.getBoundingClientRect()
    const buttonRect = button.getBoundingClientRect()
    const width = progressTip.offsetWidth
    const center = buttonRect.left + buttonRect.width / 2 - panelRect.left
    const left = Math.min(Math.max(center - width / 2, 8), panelRect.width - width - 8)

    progressTip.style.left = `${left}px`
    progressTip.style.top = `${buttonRect.bottom - panelRect.top + 2}px`
  }

  function hideProgressTip() {
    progressTip.hidden = true
  }

  function renderProgress() {
    const duration = getSlideDuration(slides[index])

    for (const [itemIndex, item] of [...progress.children].entries()) {
      const fill = item.querySelector<HTMLElement>('[data-progress-fill]')

      if (!fill) {
        continue
      }

      if (itemIndex === index) {
        item.firstElementChild?.setAttribute('aria-current', 'step')
      } else {
        item.firstElementChild?.removeAttribute('aria-current')
      }

      const amount =
        itemIndex < index
          ? 1
          : itemIndex > index
            ? 0
            : duration
              ? Math.min(elapsed / duration, 1)
              : 1
      fill.style.scale = `${amount} 1`
    }

    const slide = slides[index]

    if (slide?.type === 'stop' && duration && slide.stop.images.length > 1) {
      const nextPhotoIndex = Math.min(
        Math.floor((elapsed / duration) * slide.stop.images.length),
        slide.stop.images.length - 1,
      )

      if (nextPhotoIndex !== photoIndex) {
        animatePhotoSize(() => showPhoto(slide.stop, nextPhotoIndex), nextPhotoIndex - photoIndex)
      }
    }

    renderRouteProgress()
  }

  /** Runs a content change and eases the card from its old height to the new one. */
  /**
   * Swaps the photo and eases its frame between the old and new size. Only the photo
   * moves: the card grows from its bottom edge, so the text and buttons stay put. The
   * photos also slide a little in the direction of travel through the thumbnails.
   */
  function animatePhotoSize(update: () => void, direction: number) {
    const frame = photo?.parentElement
    const from = frame ? { height: frame.offsetHeight, width: frame.offsetWidth } : undefined
    const outgoing =
      photo && frame && direction !== 0 && !reducedMotion.matches
        ? (photo.cloneNode() as HTMLImageElement)
        : undefined

    photoAnimation?.cancel()

    if (outgoing && photo && frame) {
      // A copy of the old photo stays in place to slide out over the new one.
      outgoing.style.cssText = `position:absolute;top:0;left:0;width:${photo.offsetWidth}px;height:${photo.offsetHeight}px;max-width:none;max-height:none;pointer-events:none`
      frame.append(outgoing)
    }

    update()

    if (!frame || !from || reducedMotion.matches) {
      return
    }

    if (outgoing && photo) {
      const shift = Math.sign(direction) * PHOTO_SLIDE_DISTANCE
      const timing = { duration: 280, easing: 'cubic-bezier(0.22, 1, 0.36, 1)' }

      // Cancelled when photos change again quickly; the copy goes either way.
      void outgoing
        .animate([{ opacity: 1 }, { opacity: 0, transform: `translateX(${-shift}px)` }], timing)
        .finished.catch(() => {})
        .finally(() => outgoing.remove())
      photo.animate(
        [
          { opacity: 0, transform: `translateX(${shift}px)` },
          { opacity: 1, transform: 'none' },
        ],
        timing,
      )
    }

    const to = { height: frame.offsetHeight, width: frame.offsetWidth }

    if (from.height === to.height && from.width === to.width) {
      return
    }

    photoAnimation = frame.animate(
      [
        { height: `${from.height}px`, width: `${from.width}px` },
        { height: `${to.height}px`, width: `${to.width}px` },
      ],
      { duration: 280, easing: 'cubic-bezier(0.22, 1, 0.36, 1)' },
    )
  }

  /** Plays a live photo's motion once over its still, then fades back to the still. */
  function playLivePhoto(image: TripImage) {
    const frame = photo?.parentElement

    liveVideo?.remove()
    liveVideo = undefined

    // The stage card plays its own copy, see showStagePhoto.
    if (!image.video || !frame || reducedMotion.matches || isStageLayout()) {
      return
    }

    liveVideo = createLiveVideo(
      image.video,
      'pointer-events-none absolute inset-0 size-full object-cover transition-opacity duration-300',
    )
    frame.append(liveVideo)
    void liveVideo.play().catch(() => liveVideo?.remove())
  }

  function showPhoto(stop: TripStop, nextPhotoIndex: number) {
    const image = stop.images[nextPhotoIndex]

    if (!photo || !image) {
      return
    }

    photoIndex = nextPhotoIndex

    if (stagePhotosReady) {
      showStagePhoto(stop, nextPhotoIndex)
    }

    photo.srcset = image.srcset
    photo.src = image.src
    photo.width = image.width
    photo.height = image.height
    photo.alt = image.alt
    playLivePhoto(image)

    for (const [thumbnailIndex, thumbnail] of thumbnails.entries()) {
      if (thumbnailIndex === nextPhotoIndex) {
        thumbnail.setAttribute('aria-current', 'true')
        thumbnail.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' })
      } else {
        thumbnail.removeAttribute('aria-current')
      }
    }
  }

  function createThumbnails(stop: TripStop) {
    const list = createElement(
      'div',
      'mt-1 flex shrink-0 gap-1 overflow-x-auto overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
      '',
    )

    thumbnails = stop.images.map((image, imageIndex) => {
      const button = createElement(
        'button',
        'size-11 shrink-0 cursor-pointer overflow-hidden rounded-[0.3125rem] border-0 bg-inline p-0 opacity-55 transition-opacity duration-[140ms] hover:opacity-100 aria-[current=true]:opacity-100 aria-[current=true]:outline-2 aria-[current=true]:-outline-offset-2 aria-[current=true]:outline-tx motion-reduce:transition-none',
        '',
      ) as HTMLButtonElement
      const thumbnail = document.createElement('img')

      button.type = 'button'
      button.setAttribute('aria-label', `查看第 ${imageIndex + 1} 张照片`)
      thumbnail.className = 'pointer-events-none block size-full object-cover'
      thumbnail.src = image.thumbnail
      thumbnail.alt = ''
      thumbnail.decoding = 'async'
      button.append(thumbnail)
      button.addEventListener(
        'click',
        () => {
          const duration = getSlideDuration(slides[index]) ?? 0
          elapsed = (duration / stop.images.length) * imageIndex
          animatePhotoSize(() => showPhoto(stop, imageIndex), imageIndex - photoIndex)
          renderProgress()
        },
        { signal },
      )
      return button
    })
    list.append(...thumbnails)
    return list
  }

  function renderPlayButton() {
    const icon = playButton.firstElementChild

    playButton.setAttribute('aria-label', playing ? '暂停' : '播放')
    icon?.classList.toggle('i-lucide-pause', playing)
    icon?.classList.toggle('i-lucide-play', !playing)
    // The toolbar steps aside while playing and returns when paused or finished.
    page.toggleAttribute('data-trip-playing', playing)
    syncAmbientMusic()
  }

  /** How long the map takes to travel one leg at the chosen speed. */
  function getLegDuration() {
    return LEG_DURATION / speed
  }

  function renderSpeedButton() {
    speedButton.textContent = `${speed}×`
    speedButton.setAttribute('aria-label', `播放速度 ${speed}×`)
  }

  function renderMusicButton() {
    const icon = musicButton.firstElementChild

    musicButton.setAttribute('aria-pressed', String(musicOn))
    icon?.classList.toggle('i-lucide-volume-2', musicOn)
    icon?.classList.toggle('i-lucide-volume-x', !musicOn)
  }

  function getAmbientSource() {
    return exportMusic.value || exportMusic.options[0]?.value || ''
  }

  /** Plays the chosen track while the card playback runs, and pauses it otherwise. */
  function syncAmbientMusic() {
    const src = getAmbientSource()

    // Switched off, no trip, or the stage has its own music: release it entirely.
    if (!musicOn || !trip || staging || (ambient && ambient.src !== src)) {
      stopAmbientMusic()
    }

    if (!musicOn || !trip || staging) {
      return
    }

    // Paused playback only pauses the music, so it picks up where it was.
    if (!playing) {
      void ambient?.context.suspend()
      return
    }

    if (ambient) {
      void ambient.context.resume()
      return
    }

    const loading = { src }
    pendingAmbient = loading

    void loadMusic(src, false).then((music) => {
      if (!music || pendingAmbient !== loading) {
        void music?.context.close()
        return
      }

      pendingAmbient = undefined
      ambient = { context: music.context, gain: music.gain, source: music.source, src }
      fadeMusicIn(music)
      syncAmbientMusic()
    })
  }

  function stopAmbientMusic() {
    pendingAmbient = undefined

    if (ambient) {
      ambient.source.stop()
      void ambient.context.close()
      ambient = undefined
    }
  }

  function fadeMusicIn(music: {
    context: AudioContext
    gain: GainNode
    source: AudioBufferSourceNode
  }) {
    const now = music.context.currentTime

    music.gain.gain.setValueAtTime(0, now)
    music.gain.gain.linearRampToValueAtTime(MUSIC_VOLUME, now + MUSIC_FADE_IN)
    music.source.start()
  }

  function renderSlide() {
    const slide = slides[index]

    if (!trip || !slide) {
      return
    }

    // A stop's day, time and place are labelled on the map instead of the card.
    header.classList.toggle('sr-only', slide.type === 'stop')
    body.classList.toggle('pt-3', slide.type === 'stop')
    previousButton.disabled = index === 0
    nextButton.disabled = index === slides.length - 1

    if (slide.type === 'intro') {
      eyebrow.textContent = '旅程回放'
      title.textContent = trip.title
      body.replaceChildren(
        createElement(
          'p',
          'shrink-0 text-tx-2',
          `${trip.dateRange} · ${trip.dayCount} 天 · ${trip.stops.length} 条记录`,
        ),
        createMosaic(covers),
      )
    } else if (slide.type === 'day') {
      const weather = trip.weather[slide.day]

      eyebrow.textContent = `第 ${slide.day} 天`
      title.textContent = slide.date
      body.replaceChildren(
        ...(weather
          ? [createWeather(weather, 'mb-1 flex shrink-0 items-center gap-1.5 text-tx-2')]
          : []),
        createElement('p', 'shrink-0 text-tx-2', slide.places.join(' → ')),
      )
    } else if (slide.type === 'stop') {
      const { stop } = slide
      const text = createElement(
        'p',
        'mt-3 max-h-[min(20dvh,9rem)] shrink-0 overflow-y-auto @max-[48rem]:max-h-none overscroll-contain [scrollbar-width:none] break-words whitespace-pre-line text-tx-2 [&::-webkit-scrollbar]:hidden',
        stop.text,
      )
      const link = document.createElement('a')

      eyebrow.textContent = getStopEyebrow(stop)
      title.textContent = stop.name
      link.href = stop.href

      photo = undefined
      thumbnails = []
      photoIndex = 0

      if (stop.images.length > 0) {
        photo = document.createElement('img')
        link.className =
          'relative block min-h-0 self-center overflow-hidden rounded-md border border-border bg-inline no-underline @max-[48rem]:shrink-0'
        link.setAttribute('aria-label', `查看 ${stop.date}的记录`)
        photo.className =
          'block h-auto max-h-[min(40dvh,22rem)] w-auto max-w-full @max-[48rem]:max-h-[38dvh]'
        photo.sizes = IMAGE_SIZES
        photo.decoding = 'async'
        link.append(photo)
      } else {
        link.className = 'self-start text-[0.8125rem] text-tx-2 hover:text-tx'
        link.textContent = '查看当天记录'
      }

      // Text sits last, next to the fixed footer, so photos of other sizes don't move it.
      body.replaceChildren(
        link,
        ...(stop.images.length > 1 ? [createThumbnails(stop)] : []),
        ...(stop.text ? [text] : []),
      )
      showPhoto(stop, 0)

      for (const image of stop.images.slice(1)) {
        preloadImage(image)
      }

      const nextSlide = slides[index + 1]

      if (nextSlide?.type === 'stop' && nextSlide.stop.images[0]) {
        preloadImage(nextSlide.stop.images[0])
      }
    } else {
      const stats = document.createElement('dl')
      const replay = createElement(
        'button',
        'mt-3 inline-flex min-h-9 shrink-0 cursor-pointer self-start items-center gap-1.5 rounded-full border border-border bg-transparent px-3.5 text-[0.8125rem] text-tx hover:bg-inline',
        '重新播放',
      ) as HTMLButtonElement
      const replayIcon = createElement('span', 'i-lucide-rotate-ccw', '')

      eyebrow.textContent = '旅程回顾'
      title.textContent = trip.title
      stats.className = 'grid shrink-0 grid-cols-2 gap-1 @max-[48rem]:grid-cols-3'

      for (const [value, label] of getSummaryStats(trip)) {
        const item = createElement(
          'div',
          'flex flex-col-reverse rounded-md bg-inline px-3 py-2',
          '',
        )
        item.append(
          createElement('dt', 'text-xs text-tx-3', label),
          createElement('dd', 'font-mono text-lg tabular-nums', String(value)),
        )
        stats.append(item)
      }

      replayIcon.setAttribute('aria-hidden', 'true')
      replay.type = 'button'
      replay.prepend(replayIcon)
      replay.addEventListener('click', () => goTo(0, { play: true }), { signal })
      body.replaceChildren(stats, replay, createMosaic(covers))
    }

    renderStage(slide)
    renderProgress()
    renderPlayButton()
  }

  function renderStage(slide: Slide) {
    clearTimeout(stagePhotoTimer)
    stagePhotosReady = false

    if (!isStageLayout() || !trip) {
      if (progress.parentElement !== panel) {
        panel.prepend(progress)
      }

      return
    }

    if (progress.parentElement !== stageProgress) {
      stageProgress.append(progress)
    }

    stage.dataset.slide = slide.type
    clearStagePhotos()

    if (slide.type === 'intro') {
      stageCenter.replaceChildren(createIntroCard(trip))
      return
    }

    if (slide.type === 'summary') {
      stageCenter.replaceChildren(createSummaryCard(trip))
      return
    }

    if (slide.type === 'day') {
      stageCenter.replaceChildren(createDayCard(slide))
      return
    }

    const { stop } = slide

    stageCenter.replaceChildren()
    stageEyebrow.textContent = `第 ${stop.day} 天`
    stageTitle.textContent = stop.date
    // The photo card pops up once the camera has arrived at the stop.
    stagePhotoTimer = setTimeout(
      () => {
        createStageCard(stop, slide.stopIndex)
        stagePhotosReady = true
        showStagePhoto(stop, photoIndex)
      },
      reducedMotion.matches ? 0 : getLegDuration(),
    )
  }

  function clearStagePhotos() {
    stageCard = undefined

    for (const card of stagePhotos.children) {
      leaveStageCard(card as HTMLElement)
    }
  }

  function leaveStageCard(card: HTMLElement) {
    if (card.dataset.leaving !== undefined) {
      return
    }

    card.dataset.leaving = ''
    setTimeout(() => card.remove(), reducedMotion.matches ? 0 : 300)
  }

  /**
   * Builds the stop's card with its full text first, then gives the photo frame
   * whatever height is left, so the card always fits and the text is never cut.
   */
  function createStageCard(stop: TripStop, stopIndex: number) {
    const card = document.createElement('figure')
    const frame =
      stop.images.length > 0
        ? createElement('div', 'calendar-map-trip-polaroid__frame', '')
        : undefined

    clearStagePhotos()

    // The place, day and time are on the map and header, so the card is just the
    // photos and the Moment's words.
    if (!frame && !stop.text) {
      return
    }

    card.className = 'calendar-map-trip-polaroid'
    card.dataset.tilt = stopIndex % 2 === 0 ? 'left' : 'right'
    card.append(...(frame ? [frame] : []))

    if (stop.text) {
      const caption = document.createElement('figcaption')
      caption.append(createElement('p', 'calendar-map-trip-polaroid__text', stop.text))
      card.append(caption)
    }

    stagePhotos.append(card)
    stageCard = {
      ...(frame ? { frame } : {}),
      frameWidth: frame?.clientWidth ?? 0,
      photoMax: Math.max(stagePhotos.clientHeight - card.offsetHeight, 0),
    }
  }

  /** Cross-fades to another photo while the frame eases to its height. */
  function showStagePhoto(stop: TripStop, imageIndex: number) {
    const image = stop.images[imageIndex]
    const frame = stageCard?.frame

    if (!stageCard || !frame || !image) {
      return
    }

    const ratio = image.width / image.height
    const height = Math.min(stageCard.frameWidth / ratio, stageCard.photoMax)
    const img = document.createElement('img')
    const firstPhoto = frame.childElementCount === 0

    img.src = image.src
    img.srcset = image.srcset
    img.sizes = `${Math.round(stageCard.frameWidth)}px`
    img.alt = ''
    img.style.width = `${height * ratio}px`
    img.style.height = `${height}px`

    for (const old of frame.querySelectorAll<HTMLElement>('img, video')) {
      old.style.opacity = '0'
      setTimeout(() => old.remove(), reducedMotion.matches ? 0 : 450)
    }

    if (firstPhoto) {
      // The card's own entrance animation covers the first photo.
      frame.style.transition = 'none'
      frame.style.height = `${height}px`
      void frame.offsetHeight
      frame.style.transition = ''
    } else {
      img.style.opacity = '0'
      requestAnimationFrame(() => {
        img.style.opacity = ''
      })
      frame.style.height = `${height}px`
    }

    frame.append(img)

    // A live photo moves once over its still, as on the side card.
    if (image.video && !reducedMotion.matches) {
      const video = createLiveVideo(image.video, '')

      video.style.width = img.style.width
      video.style.height = img.style.height
      frame.append(video)
      void video.play().catch(() => video.remove())
    }
  }

  function createIntroCard(currentTrip: TripData) {
    const card = createElement('div', 'calendar-map-trip-stage-card', '')
    const coverRow = createElement('div', 'calendar-map-trip-stage-card__covers', '')

    for (const cover of covers.slice(0, 3)) {
      const image = document.createElement('img')
      image.src = cover.thumbnail
      image.alt = ''
      coverRow.append(image)
    }

    card.append(
      createElement('p', 'calendar-map-trip-stage-card__eyebrow', '旅程回放'),
      createElement('p', 'calendar-map-trip-stage-card__title', currentTrip.title),
      createElement(
        'p',
        'calendar-map-trip-stage-card__meta',
        `${currentTrip.dateRange} · ${currentTrip.dayCount} 天 · ${currentTrip.stops.length} 条记录`,
      ),
      coverRow,
    )
    return card
  }

  function createDayCard(slide: Extract<Slide, { type: 'day' }>) {
    const card = createElement('div', 'calendar-map-trip-stage-card', '')

    const weather = trip?.weather[slide.day]

    card.append(
      createElement('p', 'calendar-map-trip-stage-card__eyebrow', `第 ${slide.day} 天`),
      createElement('p', 'calendar-map-trip-stage-card__title', slide.date),
      ...(weather ? [createWeather(weather, 'calendar-map-trip-stage-card__weather')] : []),
      createElement('p', 'calendar-map-trip-stage-card__meta', slide.places.join(' → ')),
    )
    return card
  }

  function createSummaryCard(currentTrip: TripData) {
    const card = createElement('div', 'calendar-map-trip-stage-card', '')
    const stats = document.createElement('dl')

    stats.className = 'calendar-map-trip-stage-card__stats'

    for (const [value, label] of getSummaryStats(currentTrip)) {
      const item = document.createElement('div')
      item.append(createElement('dt', '', label), createElement('dd', '', String(value)))
      stats.append(item)
    }

    card.append(
      createElement('p', 'calendar-map-trip-stage-card__eyebrow', '这趟旅程'),
      createElement('p', 'calendar-map-trip-stage-card__title', currentTrip.title),
      stats,
      // Where a viewer of the video can find the trip.
      createElement('p', 'calendar-map-trip-stage-card__site', `${new URL(SITE_URL).host}/trip/`),
    )
    return card
  }

  /** Notes when a day or the summary starts in the recording. */
  function addChapter(slide: Slide) {
    if (recordingStart === undefined || (slide.type !== 'day' && slide.type !== 'summary')) {
      return
    }

    const label = slide.type === 'day' ? `第 ${slide.day} 天 · ${slide.date}` : '旅程回顾'
    const time = Math.floor((performance.now() - recordingStart) / 1000)
    const last = chapters.at(-1)

    // Video sites want chapters at least ten seconds long, so a short one gives way.
    if (last && time - last.time < 10) {
      last.label = label
    } else {
      chapters.push({ label, time })
    }
  }

  /**
   * The waypoints the map passes before a slide shows, as `[first point, count]`.
   * Waypoints from the day before a stop (leaving the evening before) are passed
   * before that day's opener, so the new day starts where it really started.
   */
  function getWaypointsBefore(slide: Slide): [number, number] {
    if (slide.type === 'summary') {
      return [stopPointEnds.at(-1) ?? 0, trip?.trailingWaypoints.length ?? 0]
    }

    if (slide.type === 'intro') {
      return [0, 0]
    }

    const start = getStopPointStart(slide.stopIndex)
    const stop = trip!.stops[slide.stopIndex]!
    const opensDay = trip!.stops[slide.stopIndex - 1]?.day !== stop.day
    const earlier = opensDay ? getEarlierDayWaypointCount(slide.stopIndex) : 0

    return slide.type === 'day'
      ? [start, earlier]
      : [start + earlier, stop.waypoints.length - earlier]
  }

  /** How many of a stop's waypoints were passed on an earlier day; they come first. */
  function getEarlierDayWaypointCount(stopIndex: number) {
    const stop = trip?.stops[stopIndex]
    return stop?.waypoints.filter((waypoint) => waypoint.day < stop.day).length ?? 0
  }

  /** The slide a shared link names: a stop's card, or its day's opener when it starts the day. */
  function getHashSlideIndex() {
    const anchor = decodeURIComponent(location.hash.slice(1))
    const stopIndex = trip?.stops.findIndex((stop) => stop.anchor === anchor) ?? -1

    if (!anchor || stopIndex === -1) {
      return 0
    }

    return slides.findIndex(
      (slide) => (slide.type === 'day' || slide.type === 'stop') && slide.stopIndex === stopIndex,
    )
  }

  /** Keeps the address bar pointing at the current card, so it can be shared. */
  function syncHash(slide: Slide) {
    const anchor =
      slide.type === 'stop' || slide.type === 'day' ? trip?.stops[slide.stopIndex]?.anchor : ''
    const url = new URL(location.href)

    url.hash = anchor ?? ''

    if (url.href !== location.href) {
      history.replaceState(history.state, '', url)
    }
  }

  function goTo(nextIndex: number, options: { play?: boolean } = {}) {
    if (!trip || nextIndex < 0 || nextIndex >= slides.length) {
      return
    }

    const slide = slides[nextIndex]!
    // Skipping ahead while the map is still travelling jumps straight to the stop.
    const stepForward = nextIndex === index + 1 && !traveling && !reducedMotion.matches

    travelToken += 1
    traveling = false
    currentWaypointPoint = -1

    if (options.play !== undefined) {
      playing = options.play
    }

    const [firstWaypoint, waypointCount] = getWaypointsBefore(slide)

    if (stepForward && waypointCount > 0) {
      // The current card stays up while the map passes the waypoints.
      void travelThroughWaypoints(firstWaypoint, waypointCount, () =>
        showSlide(nextIndex, firstWaypoint + waypointCount - 1),
      )
      return
    }

    showSlide(nextIndex, stepForward ? undefined : -1)
  }

  /**
   * Shows a slide's card and camera. `legStart` is where the route animates from:
   * `undefined` for the previous place, -1 to draw it instantly.
   */
  function showSlide(nextIndex: number, legStart?: number) {
    const slide = slides[nextIndex]!

    // Taken before rendering, which already starts the route onward from this stop.
    const arrived =
      slide.type === 'stop' && drawnPointCount >= getStopPlaceStart(slide.stopIndex) + 1

    index = nextIndex
    elapsed = 0
    syncHash(slide)
    addChapter(slide)
    // Autoplay stays quiet, like a carousel; stepping through by hand names each card.
    announcer.textContent = playing ? '' : getSlideLabel(slide)

    if (!getSlideDuration(slide)) {
      playing = false
    }

    renderSlide()

    routeProgress = undefined
    followedStop = -1
    let longArrival: Promise<void> | undefined

    if (slide.type === 'stop') {
      const placeStart = getStopPlaceStart(slide.stopIndex)
      const arriveAt = placeStart + 1

      // The previous card's route usually arrived already; only a skip ahead or
      // the last leg after waypoints still has to be drawn.
      if (legStart === -1 || (legStart === undefined && arrived)) {
        cancelLeg()
        drawnPointCount = arriveAt
        setRoute(drawnPointCount)
      } else {
        const from = legStart ?? Math.max(placeStart - 1, 0)
        const leg = drawLeg(from, arriveAt)

        if (
          getDistanceKm(points[from]!.coordinates, points[placeStart]!.coordinates) >
          NEARBY_RADIUS_KM
        ) {
          longArrival = leg
          frameLeg(from, placeStart)
        }
      }
    } else if (slide.type === 'day') {
      // Usually the previous stop's route already reached here; jumps set it directly.
      if (legStart === -1 || drawnPointCount === 0) {
        cancelLeg()
        drawnPointCount = Math.min(
          getStopPointStart(slide.stopIndex) +
            Math.max(getEarlierDayWaypointCount(slide.stopIndex), 1),
          points.length,
        )
        setRoute(drawnPointCount)
      }
    } else {
      cancelLeg()
      drawnPointCount = slide.type === 'intro' ? 0 : points.length
      setRoute(drawnPointCount)
    }

    updateStops()

    if (longArrival) {
      // Zooming in to the stop waits until the drive there has been drawn in view.
      const token = travelToken
      void longArrival.then(() => {
        if (token === travelToken && index === nextIndex) {
          moveCamera()
        }
      })
    } else {
      moveCamera()
    }

    renderRouteProgress()

    if (staging && slide.type === 'summary') {
      const music = staging.music

      if (music) {
        const now = music.context.currentTime
        const fadeStart = now + Math.max(EXPORT_SUMMARY_HOLD / 1000 - MUSIC_FADE_OUT, 0)

        music.gain.gain.setValueAtTime(MUSIC_VOLUME, fadeStart)
        music.gain.gain.linearRampToValueAtTime(0, fadeStart + MUSIC_FADE_OUT)
      }

      staging.stopTimer = setTimeout(stopStage, EXPORT_SUMMARY_HOLD)
    }
  }

  function setExportOptionsOpen(open: boolean, mode: StageMode = stageMode) {
    stageMode = mode
    exportOptions.setAttribute('aria-label', mode === 'present' ? '放映' : '导出视频')
    exportOptions.hidden = !open
    controls.hidden = open
    actions.hidden = open
  }

  /**
   * Plays the trip once from the start in the story layout. Recording captures the
   * tab, cropped to the stage, and downloads the video; presenting goes full screen.
   */
  async function startStage(ratio: ExportRatio, mode: StageMode) {
    const currentTrip = trip

    setExportOptionsOpen(false)

    if (!currentTrip || staging) {
      return
    }

    let stream: MediaStream | undefined

    // Both requests need the click that started this, so they come first.
    if (mode === 'record') {
      try {
        stream = await navigator.mediaDevices.getDisplayMedia({
          audio: false,
          preferCurrentTab: true,
          selfBrowserSurface: 'include',
          video: { displaySurface: 'browser', frameRate: 30 },
        } as DisplayMediaStreamOptions)
      } catch {
        return
      }
    } else {
      // Full screen is a nicety; some browsers never settle the request, so don't wait.
      void document.documentElement.requestFullscreen?.().catch(() => {})
    }

    const token = {}
    stageToken = token
    stopAmbientMusic()
    page.dataset.exportRatio = ratio
    page.toggleAttribute('data-trip-presenting', mode === 'present')
    getMap()?.resize()

    const track = stream?.getVideoTracks()[0] as CroppableTrack | undefined
    const cropTarget = (window as Window & { CropTarget?: CropTargetConstructor }).CropTarget

    try {
      if (track?.cropTo && cropTarget) {
        await track.cropTo(await cropTarget.fromElement(page))
      }
    } catch {
      // Without Region Capture the video includes the whole tab.
    }

    const music = await loadMusic(exportMusic.value, mode === 'record')
    let recorder: MediaRecorder | undefined

    if (stream) {
      const recorded = music?.destination
        ? new MediaStream([
            ...stream.getVideoTracks(),
            ...music.destination.stream.getAudioTracks(),
          ])
        : stream
      const mimeType = EXPORT_VIDEO_TYPES.find((type) => MediaRecorder.isTypeSupported(type))
      const chunks: Blob[] = []
      const activeRecorder = new MediaRecorder(recorded, {
        ...(mimeType ? { mimeType } : {}),
        videoBitsPerSecond: 12_000_000,
      })

      activeRecorder.addEventListener('dataavailable', (event) => {
        if (event.data.size > 0) {
          chunks.push(event.data)
        }
      })
      activeRecorder.addEventListener('stop', () => {
        const type = activeRecorder.mimeType || 'video/webm'

        const name = `${currentTrip.title}-${ratio === 'portrait' ? '竖屏' : '横屏'}`
        const chapterText = chapters
          .map(({ label, time }) => `${formatChapterTime(time)} ${label}`)
          .join('\n')

        finishStage()
        downloadBlob(new Blob(chunks, { type }), `${name}.${type.includes('mp4') ? 'mp4' : 'webm'}`)
        downloadBlob(new Blob([`${chapterText}\n`], { type: 'text/plain' }), `${name}-章节.txt`)
      })
      track?.addEventListener('ended', stopStage)
      recorder = activeRecorder
    }

    staging = {
      mode,
      ...(music
        ? { music: { context: music.context, gain: music.gain, source: music.source } }
        : {}),
      ...(recorder ? { recorder } : {}),
      ...(stream ? { stream } : {}),
    }

    // Reset to a still intro first: the camera jumps to the whole route and the
    // tiles load before the first frame, so playback doesn't open mid-flight.
    goTo(0, { play: false })
    fitTrip({ instant: true })
    await waitForMapIdle()

    if (stageToken !== token) {
      return
    }

    recorder?.start(1000)

    if (recorder) {
      recordingStart = performance.now()
      chapters = [{ label: currentTrip.title, time: 0 }]
    }

    if (music) {
      fadeMusicIn(music)
    }

    elapsed = 0
    playing = true
    renderPlayButton()
  }

  function waitForMapIdle() {
    const map = getMap()

    return new Promise<void>((resolve) => {
      const timeout = setTimeout(resolve, 5000)

      if (!map) {
        clearTimeout(timeout)
        resolve()
        return
      }

      map.once('idle', () => {
        clearTimeout(timeout)
        // One more frame so the settled map is what playback starts on.
        requestAnimationFrame(() => resolve())
      })
      map.triggerRepaint()
    })
  }

  /**
   * Decodes a background track into a looping source that plays out loud and, when
   * recording, also feeds a stream for the video's audio track.
   */
  async function loadMusic(src: string, record: boolean) {
    if (!src) {
      return undefined
    }

    try {
      const context = new AudioContext()
      const buffer = await fetch(src)
        .then((response) => response.arrayBuffer())
        .then((data) => context.decodeAudioData(data))
      const source = context.createBufferSource()
      const gain = context.createGain()
      const destination = record ? context.createMediaStreamDestination() : undefined

      source.buffer = buffer
      source.loop = true
      gain.gain.value = 0
      source.connect(gain)
      gain.connect(context.destination)

      if (destination) {
        gain.connect(destination)
      }

      await context.resume()
      return { context, gain, source, ...(destination ? { destination } : {}) }
    } catch {
      // Playback without music is better than none.
      return undefined
    }
  }

  function stopStage() {
    if (staging?.recorder?.state === 'recording') {
      staging.recorder.stop()
    } else {
      finishStage()
    }
  }

  function finishStage() {
    stageToken = undefined
    recordingStart = undefined

    if (!staging) {
      return
    }

    const { mode, music, stopTimer, stream } = staging

    clearTimeout(stopTimer)

    for (const track of stream?.getTracks() ?? []) {
      track.stop()
    }

    if (music) {
      music.source.stop()
      void music.context.close()
    }

    staging = undefined
    delete page.dataset.exportRatio
    page.removeAttribute('data-trip-presenting')

    if (mode === 'present' && document.fullscreenElement) {
      void document.exitFullscreen().catch(() => {})
    }

    getMap()?.resize()
    // Back to the side card for whatever slide is showing.
    renderSlide()
  }

  function tick(now: number) {
    frame = requestAnimationFrame(tick)

    const delta = lastFrameTime === undefined ? 0 : now - lastFrameTime
    const duration = getSlideDuration(slides[index])

    lastFrameTime = now

    // The card's timer waits while the map travels between waypoints or the mouse rests on it.
    if (!playing || !duration || document.hidden || traveling || (hovering && !staging)) {
      return
    }

    elapsed += delta * speed
    renderProgress()

    if (elapsed >= duration) {
      goTo(index + 1)
    }
  }

  function start(id: string) {
    if (!tripTitles.has(id) || trip?.id === id || requestedId === id) {
      return
    }

    const loadedTrip = loadedTrips.get(id)

    if (loadedTrip) {
      begin(loadedTrip)
      return
    }

    stop()
    requestedId = id
    void loadTrip(id).then((data) => {
      if (requestedId !== id) {
        return
      }

      requestedId = undefined

      if (data) {
        begin(data)
      } else {
        onClose()
      }
    })
  }

  /** Fetches a trip's data once; a failed fetch is tried again next time. */
  function loadTrip(id: string) {
    let request = tripRequests.get(id)

    if (!request) {
      request = fetch(`/trip/${id}/data.json`, { signal })
        .then(async (response) => {
          if (!response.ok) {
            return undefined
          }

          const data = (await response.json()) as TripData
          loadedTrips.set(id, data)
          return data
        })
        .catch(() => undefined)
        .then((data) => {
          if (!data) {
            tripRequests.delete(id)
          }

          return data
        })
      tripRequests.set(id, request)
    }

    return request
  }

  /** Loads every trip's data ahead of time, when a switch to another trip looks likely. */
  function prefetch() {
    for (const id of tripTitles.keys()) {
      if (!loadedTrips.has(id)) {
        void loadTrip(id)
      }
    }
  }

  function begin(nextTrip: TripData) {
    stop()
    trip = nextTrip
    covers = pickCovers(nextTrip.covers, nextTrip.stops, 9)
    exportMusic.value = nextTrip.music ?? exportMusic.options[0]?.value ?? ''
    const toPoint = (
      place: TripPlace | TripWaypoint,
      stopIndex: number,
      waypoint: boolean,
    ): TripPoint => ({
      coordinates: [place.longitude, place.latitude],
      ...('day' in place ? { day: place.day, eyebrow: getStopEyebrow(place) } : {}),
      name: place.name,
      stopIndex,
      ...('time' in place && place.time ? { time: place.time } : {}),
      waypoint,
    })

    points = [
      ...nextTrip.stops.flatMap((stop, stopIndex) => [
        ...stop.waypoints.map((waypoint) => toPoint(waypoint, stopIndex, true)),
        ...stop.places.map((place) => toPoint(place, stopIndex, false)),
      ]),
      ...nextTrip.trailingWaypoints.map((waypoint) =>
        toPoint(waypoint, nextTrip.stops.length, true),
      ),
    ]
    stopPointEnds = getStopPointEnds(nextTrip.stops)
    slides = buildSlides(nextTrip.stops)
    progress.replaceChildren(
      ...slides.map((slide, slideIndex) => {
        const item = createElement('li', 'flex-1', '')
        // A taller transparent hit area around the thin bar, so a segment is easy to tap.
        const button = createElement(
          'button',
          'group -my-2 block w-full cursor-pointer border-0 bg-transparent py-2',
          '',
        ) as HTMLButtonElement
        const bar = createElement(
          'span',
          'block h-0.5 overflow-hidden rounded-full bg-border transition-colors duration-[140ms] group-hover:bg-tx-3 motion-reduce:transition-none',
          '',
        )
        const fill = createElement('span', 'block h-full origin-left bg-tx [scale:0_1]', '')

        button.type = 'button'
        button.setAttribute('aria-label', getSlideLabel(slide))
        button.addEventListener('click', () => goTo(slideIndex), { signal })
        button.addEventListener(
          'pointerenter',
          (event) => {
            if (event.pointerType === 'mouse') {
              showProgressTip(button)
            }
          },
          { signal },
        )
        button.addEventListener('pointerleave', hideProgressTip, { signal })
        button.addEventListener(
          'focus',
          () => {
            if (button.matches(':focus-visible')) {
              showProgressTip(button)
            }
          },
          { signal },
        )
        button.addEventListener('blur', hideProgressTip, { signal })
        bar.dataset.progressBar = ''
        fill.dataset.progressFill = ''
        bar.append(fill)
        button.append(bar)
        item.append(button)
        return item
      }),
    )
    panel.hidden = false
    drawnPointCount = 0
    addLayers()
    index = -1
    goTo(getHashSlideIndex(), { play: !reducedMotion.matches })
    lastFrameTime = undefined
    frame = requestAnimationFrame(tick)
  }

  function stop() {
    requestedId = undefined

    if (!trip) {
      return
    }

    stopStage()
    stopAmbientMusic()
    setExportOptionsOpen(false)
    page.removeAttribute('data-trip-playing')

    if (frame !== undefined) {
      cancelAnimationFrame(frame)
      frame = undefined
    }

    travelToken += 1
    traveling = false
    cancelLeg()
    removeStopPopups()
    removeLayers()
    trip = undefined
    slides = []
    playing = false
    panel.hidden = true
    progress.replaceChildren()
    hideProgressTip()
    body.replaceChildren()
  }

  previousButton.addEventListener('click', () => goTo(index - 1), { signal })
  nextButton.addEventListener('click', () => goTo(index + 1), { signal })
  closeButton.addEventListener('click', onClose, { signal })
  panel.addEventListener(
    'pointerenter',
    (event) => {
      hovering = event.pointerType === 'mouse'
    },
    { signal },
  )
  panel.addEventListener(
    'pointerleave',
    () => {
      hovering = false
    },
    { signal },
  )
  exportButton.hidden =
    !navigator.mediaDevices?.getDisplayMedia || typeof MediaRecorder === 'undefined'
  exportButton.addEventListener('click', () => setExportOptionsOpen(true, 'record'), { signal })
  presentButton.addEventListener('click', () => setExportOptionsOpen(true, 'present'), { signal })
  page
    .querySelector('[data-calendar-map-trip-stage-close]')
    ?.addEventListener('click', stopStage, { signal })
  document.addEventListener(
    'fullscreenchange',
    () => {
      // Leaving full screen (Esc, or the browser's own control) ends a presentation.
      if (staging?.mode === 'present' && !document.fullscreenElement) {
        stopStage()
      }
    },
    { signal },
  )
  renderSpeedButton()
  speedButton.addEventListener(
    'click',
    () => {
      speed = SPEEDS[(SPEEDS.indexOf(speed as (typeof SPEEDS)[number]) + 1) % SPEEDS.length]!
      writeSpeedPreference(speed)
      renderSpeedButton()
    },
    { signal },
  )
  renderMusicButton()
  musicButton.addEventListener(
    'click',
    () => {
      musicOn = !musicOn
      writeMusicPreference(musicOn)
      renderMusicButton()
      syncAmbientMusic()
    },
    { signal },
  )
  exportMusic.addEventListener('change', syncAmbientMusic, { signal })
  page
    .querySelector('[data-calendar-map-trip-export-cancel]')
    ?.addEventListener('click', () => setExportOptionsOpen(false), { signal })

  for (const button of page.querySelectorAll<HTMLButtonElement>(
    '[data-calendar-map-trip-export-ratio]',
  )) {
    button.addEventListener(
      'click',
      () => void startStage(button.dataset.calendarMapTripExportRatio as ExportRatio, stageMode),
      { signal },
    )
  }
  playButton.addEventListener(
    'click',
    () => {
      if (!playing && !getSlideDuration(slides[index])) {
        goTo(0, { play: true })
        return
      }

      playing = !playing
      renderPlayButton()
    },
    { signal },
  )
  window.addEventListener(
    'keydown',
    (event) => {
      if (!trip || event.defaultPrevented || event.target instanceof HTMLSelectElement) {
        return
      }

      if (staging && event.key === 'Escape') {
        event.preventDefault()
        stopStage()
        return
      }

      // A focused control keeps Space for itself.
      if (
        event.key === ' ' &&
        !(event.target instanceof HTMLElement && event.target.closest('button, input, textarea, a'))
      ) {
        event.preventDefault()
        playButton.click()
      } else if (event.key === 'ArrowLeft') {
        goTo(index - 1)
      } else if (event.key === 'ArrowRight') {
        goTo(index + 1)
      }
    },
    { signal },
  )
  signal.addEventListener('abort', stop)

  return {
    getPageTitle: (id: string) => tripTitles.get(id),
    has: (id: string) => tripTitles.has(id),
    prefetch,
    get activeId() {
      return trip?.id ?? requestedId
    },
    /** Re-adds the route layers after the map style changes. */
    restoreLayers: addLayers,
    start,
    stop,
  }
}

function preloadImage(source: TripImage) {
  const image = new Image()
  image.sizes = IMAGE_SIZES
  image.srcset = source.srcset
  image.src = source.src
}

/** A grid of each stop's first photo for the intro and summary cards. */
/**
 * The trip's chosen covers, or else the first photo of each day followed by the
 * first photo of each remaining stop.
 */
function createMosaic(covers: TripImage[]) {
  // Narrow screens keep one row so the map stays visible above the card.
  const mosaic = createElement(
    'div',
    'mt-3 grid shrink-0 grid-cols-3 gap-1 @max-[48rem]:[&>:nth-child(n+4)]:hidden',
    '',
  )

  mosaic.setAttribute('aria-hidden', 'true')

  for (const cover of covers) {
    const image = document.createElement('img')
    image.className = 'block aspect-square w-full rounded-[0.3125rem] bg-inline object-cover'
    image.src = cover.thumbnail
    image.alt = ''
    image.decoding = 'async'
    mosaic.append(image)
  }

  return mosaic
}

function readMusicPreference() {
  try {
    return localStorage.getItem(MUSIC_PREFERENCE_KEY) === 'on'
  } catch {
    return false
  }
}

function readSpeedPreference() {
  try {
    const value = Number(localStorage.getItem(SPEED_PREFERENCE_KEY))
    return SPEEDS.find((speed) => speed === value) ?? 1
  } catch {
    return 1
  }
}

function writeSpeedPreference(speed: number) {
  try {
    localStorage.setItem(SPEED_PREFERENCE_KEY, String(speed))
  } catch {
    // The choice just won't be remembered.
  }
}

function writeMusicPreference(on: boolean) {
  try {
    localStorage.setItem(MUSIC_PREFERENCE_KEY, on ? 'on' : 'off')
  } catch {
    // The choice just won't be remembered.
  }
}

function formatChapterTime(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')

  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
}

function getSlideDay(slide: Slide | undefined) {
  return slide?.type === 'day' ? slide.day : slide?.type === 'stop' ? slide.stop.day : undefined
}

function getSlideLabel(slide: Slide) {
  if (slide.type === 'intro') {
    return '开头'
  }

  if (slide.type === 'day') {
    return `第 ${slide.day} 天 · ${slide.date}`
  }

  return slide.type === 'summary' ? '旅程回顾' : `第 ${slide.stop.day} 天 · ${slide.stop.name}`
}

function getStopEyebrow({ date, day, time }: { date: string; day: number; time?: string }) {
  return [`第 ${day} 天`, date, time].filter(Boolean).join(' · ')
}

function getDistanceKm(from: [number, number], to: [number, number]) {
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180
  const latitudeDelta = toRadians(to[1] - from[1])
  const longitudeDelta = toRadians(to[0] - from[0])
  const a =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(toRadians(from[1])) * Math.cos(toRadians(to[1])) * Math.sin(longitudeDelta / 2) ** 2

  return 2 * 6371 * Math.asin(Math.sqrt(a))
}

function getPlanarDistance(from: [number, number], to: [number, number]) {
  return Math.hypot(to[0] - from[0], to[1] - from[1])
}

function lineFeature(coordinates: [number, number][]) {
  return {
    geometry: { coordinates, type: 'LineString' as const },
    properties: {},
    type: 'Feature' as const,
  }
}

/** A muted live photo clip that plays once, then fades back to the still under it. */
/** A Lucide icon as an image, read from the mask its utility class generates. */
async function loadIconImage(className: string) {
  const probe = document.createElement('span')

  probe.className = className
  probe.style.position = 'absolute'
  probe.style.visibility = 'hidden'
  document.body.append(probe)

  const style = getComputedStyle(probe)
  const mask = style.maskImage || style.getPropertyValue('-webkit-mask-image')
  const url = /url\(["']?(.*?)["']?\)$/.exec(mask)?.[1]

  probe.remove()

  if (!url) {
    return undefined
  }

  const image = new Image()
  image.src = url

  try {
    await image.decode()
    return image
  } catch {
    return undefined
  }
}

function createLiveVideo(src: string, className: string) {
  const video = document.createElement('video')

  video.className = className
  video.muted = true
  video.playsInline = true
  video.src = src
  video.setAttribute('aria-hidden', 'true')
  video.addEventListener(
    'ended',
    () => {
      video.style.opacity = '0'
      setTimeout(() => video.remove(), 450)
    },
    { once: true },
  )
  return video
}

function createWeather(weather: { icon: string; label: string }, className: string) {
  const element = createElement('p', className, '')
  const icon = createElement('span', weather.icon, '')

  icon.setAttribute('aria-hidden', 'true')
  element.append(icon, weather.label)
  return element
}

function createElement(tag: string, className: string, text: string) {
  const element = document.createElement(tag)
  element.className = className
  element.textContent = text
  return element
}

function parseJson<T>(page: HTMLElement, selector: string, fallback: T): T {
  const text = page.querySelector(selector)?.textContent

  try {
    return text ? (JSON.parse(text) as T) : fallback
  } catch {
    return fallback
  }
}

function queryRequired<T extends Element>(root: ParentNode, selector: string) {
  const element = root.querySelector<T>(selector)

  if (!element) {
    throw new Error(`Missing required calendar map element: ${selector}`)
  }

  return element
}

function getSummaryStats(trip: TripData) {
  return [
    [trip.dayCount, '天'],
    [trip.stops.length, '条记录'],
    [trip.placeCount, '个地点'],
    [trip.photoCount, '张照片'],
    [trip.distanceKm.toLocaleString('zh-CN'), '公里'],
    [trip.drivingHours, '小时车程'],
  ] as const
}
