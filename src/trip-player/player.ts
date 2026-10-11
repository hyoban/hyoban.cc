import type { Map as MapboxMap } from 'mapbox-gl'
import type { Coordinates } from '../geo'
import { createCamera } from './camera'
import {
  canAutoplayLiveClips,
  createElement,
  createLiveVideo,
  createMosaic,
  createWeather,
  downloadBlob,
  IMAGE_SIZES,
  parseJson,
  preloadImage,
  preloadThumbnail,
  queryRequired,
} from './dom'
import { createChapterLog, getSummaryStats } from './labels'
import { closeMusic, fadeMusicIn, fadeMusicOut, loadMusic, type Music } from './music'
import {
  getNextSpeed,
  SPEEDS,
  readMusicPreference,
  readRatioPreference,
  readSafeAreaPreference,
  readSpeedPreference,
  writeMusicPreference,
  writeRatioPreference,
  writeSafeAreaPreference,
  writeSpeedPreference,
} from './preferences'
import {
  easeInOut,
  getRouteLine as buildRouteLine,
  getTravelMode,
  measureLine,
  walkLine,
  type MeasuredLine,
} from './route'
import { createMapLabels } from './map-labels'
import { createRouteLayers, type StopState } from './route-layers'
import {
  buildSlides,
  getSlideDay,
  getSlideDuration,
  getSlideLabel,
  getStopEyebrow,
  getStopPointEnds,
  getStopPointStart as getPointStart,
  getWaypointsBefore as getWaypointRange,
  pickCovers,
} from './slides'
import { createDayCard, createIntroCard, createSummaryCard, getTripMeta } from './stage-cards'
import type {
  MapLabel,
  Slide,
  TripData,
  TripImage,
  TripPlace,
  TripPoint,
  TripStop,
  TripSummary,
  TripWaypoint,
} from './types'

// Plays a trip back on the map: the route draws itself from place to place while a
// card shows each stop's photos and words. The same playback can run in a story
// layout to present full screen or to record as a video.

type TripPlayerOptions = {
  getMap: () => MapboxMap | undefined
  onClose: () => void
  page: HTMLElement
  signal: AbortSignal
}

const WAYPOINT_PAUSE = 2000
// How far photos slide when the card moves to the next or previous one.
const PHOTO_SLIDE_DISTANCE = 12
// Long enough to see both photos; the new one fades in over the old one.
const PHOTO_SWITCH = { duration: 600, easing: 'cubic-bezier(0.4, 0, 0.2, 1)' }
const LEG_DURATION = 2200
const STOP_ZOOM = 9
// The closest the camera comes in while following a short drive.
const NEARBY_MAX_ZOOM = 13
// How long the summary card stays in an exported video before it ends.
const EXPORT_SUMMARY_HOLD = 4000
// avc1 keeps the codec setup in the file header; QuickTime can't play avc3 MP4s.
const EXPORT_VIDEO_TYPES = [
  'video/mp4;codecs=avc1,mp4a.40.2',
  'video/mp4',
  'video/webm;codecs=vp9,opus',
  'video/webm',
]
// How long the pointer rests before the presentation's exit button fades away.
const CONTROLS_IDLE = 2500
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
// The address bar follows the playing card so it can be shared, but reloading the page
// should start the trip over rather than resume mid-way. Only the first trip started
// after a reload ignores the card; links followed later in the same page still work.
let reloadPending =
  (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)
    ?.type === 'reload'

type ExportRatio = 'landscape' | 'portrait'
// The story layout either plays on screen or is recorded to a video file.
type StageMode = 'present' | 'record'

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
  const stageSafe = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage-safe]')
  const safeAreaInput = queryRequired<HTMLInputElement>(page, '[data-calendar-map-trip-safe-area]')
  const safeAreaRow = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-safe-area-row]')
  const setupTitle = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-setup-title]')
  const startButton = queryRequired<HTMLButtonElement>(
    page,
    '[data-calendar-map-trip-export-start]',
  )
  const ratioButtons = [
    ...page.querySelectorAll<HTMLButtonElement>('[data-calendar-map-trip-export-ratio]'),
  ]
  let safeArea = readSafeAreaPreference()
  let ratio: ExportRatio = readRatioPreference()
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
  const shortcutsDialog = queryRequired<HTMLDialogElement>(
    page,
    '[data-calendar-map-trip-shortcuts]',
  )
  let staging:
    | {
        mode: StageMode
        music?: Music
        recorder?: MediaRecorder
        stopTimer?: ReturnType<typeof setTimeout>
        stream?: MediaStream
      }
    | undefined
  let stageMode: StageMode = 'record'
  // Where each day starts in the video being recorded, for the description's chapters.
  let recording: { chapters: ReturnType<typeof createChapterLog>; start: number } | undefined
  // Music for the ordinary card playback, switched on with the music button.
  let ambient: (Music & { src: string }) | undefined
  let musicOn = readMusicPreference()
  let pendingAmbient: { src: string } | undefined
  let stageToken: object | undefined
  const mapLabels = createMapLabels({
    getBlocked: () => {
      const shown = (element: Element | null | undefined) =>
        element &&
        element.getClientRects().length > 0 &&
        getComputedStyle(element).visibility !== 'hidden'
          ? [element.getBoundingClientRect()]
          : []

      return isStageLayout()
        ? [
            ...outside(stageSafe.getBoundingClientRect(), page.getBoundingClientRect()),
            ...shown(stageHeader),
            ...[...stagePhotos.children].flatMap(shown),
            ...shown(stageProgress),
            ...shown(page.querySelector('[data-calendar-map-trip-stage-close]')),
          ]
        : [...shown(panel), ...shown(page.querySelector('.calendar-map-toolbar'))]
    },
    getMap,
    // Every stop's dot, and the car, ship or plane while the route is drawn.
    getMarkers: () => [
      ...points.map((point) => ({
        coordinates: point.coordinates,
        radius: point.waypoint ? 7 : 10,
      })),
      ...(routeTip ? [{ coordinates: routeTip, radius: 16 }] : []),
    ],
  })
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
  const camera = createCamera({
    // The tip while the route is drawn, else the last place it reached.
    getFocus: () => routeTip ?? points[Math.max(drawnPointCount - 1, 0)]?.coordinates,
    getMap,
    getPadding: () => getCameraPadding(),
    getSpeed: () => speed,
    reducedMotion,
  })
  const layers = createRouteLayers(getMap, () => ({
    ink: getComputedStyle(page).getPropertyValue('--color-tx').trim() || '#21201c',
    paper: getComputedStyle(page).getPropertyValue('--color-bg').trim() || '#fdfdfc',
  }))
  let frame: number | undefined
  let lastFrameTime: number | undefined
  let legFrame: number | undefined
  let finishLeg: (() => void) | undefined
  // Bumped on every slide change so an unfinished trip between waypoints stops.
  let travelToken = 0
  let traveling = false
  let currentWaypointPoint = -1
  let drawnPointCount = 0
  // The onward drive during a stop's card, timed from when the drive there finished.
  // Where the route is being drawn up to right now, for the camera to follow.
  let routeTip: Coordinates | undefined
  let routeProgress: (MeasuredLine & { startedAt: number; stopIndex: number }) | undefined
  let photoIndex = 0
  let photo: HTMLImageElement | undefined
  let thumbnails: HTMLButtonElement[] = []
  let photoAnimation: Animation | undefined
  let liveVideo: HTMLVideoElement | undefined
  // A mouse resting on the card holds the current slide so it can be read.
  let hovering = false
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  // Photos already asked for, so going back and forth doesn't ask again.
  const preloaded = new Set<string>()
  // Photos decoded ahead while recording, so a card never opens on a blank frame.
  const decodedPhotos = new Map<string, Promise<void>>()
  const readyPhotos = new Set<string>()

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

      const safe = stageSafe.getBoundingClientRect()

      return page.dataset.exportRatio === 'landscape'
        ? { bottom: 64, left: 96, right: pageRect.right - photos.left + 96, top }
        : {
            bottom: pageRect.bottom - photos.top + 16,
            left: safe.left - pageRect.left + 48,
            right: pageRect.right - safe.right + 48,
            top,
          }
    }

    if (isNarrow()) {
      // Leaves room for the one-row toolbar, which returns when playback pauses.
      return { bottom: panel.offsetHeight + 40, left: 40, right: 40, top: 72 }
    }

    // The toolbar is hidden during playback, so the top needs little room.
    return { bottom: 64, left: panel.offsetWidth + 64, right: 64, top: 64 }
  }

  function getStopPointStart(stopIndex: number) {
    return getPointStart(stopPointEnds, stopIndex)
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
      routeProgress = {
        ...measureLine(getRouteLine(from, to + 1)),
        startedAt: elapsed,
        stopIndex: slide.stopIndex,
      }
    }

    // Starts from rest once any drive here is done, and arrives as the card ends.
    const left = duration - routeProgress.startedAt
    const fraction = left > 0 ? Math.min((elapsed - routeProgress.startedAt) / left, 1) : 1

    if (fraction >= 1 || routeProgress.total === 0) {
      drawnPointCount = fraction >= 1 ? to + 1 : from + 1
      setRoute(drawnPointCount)
      return
    }

    drawnPointCount = from + 1
    setRoute(from + 1, walkLine(routeProgress, easeInOut(fraction)))
  }

  /** The parts of `outer` around `inner`: what a short-video app's overlays cover. */
  function outside(inner: DOMRect, outer: DOMRect) {
    return [
      new DOMRect(outer.left, outer.top, outer.width, inner.top - outer.top),
      new DOMRect(outer.left, inner.bottom, outer.width, outer.bottom - inner.bottom),
      new DOMRect(outer.left, inner.top, inner.left - outer.left, inner.height),
      new DOMRect(inner.right, inner.top, outer.right - inner.right, inner.height),
    ].filter((rect) => rect.width > 0 && rect.height > 0)
  }

  /** Adds the route layers, also after the map style changes and drops them. */
  function addLayers() {
    if (trip && layers.add(getRouteLine(0, points.length))) {
      setRoute(drawnPointCount)
      updateStops()
    }
  }

  /** The line from `points[from]` to `points[to - 1]`, along roads where a route is known. */
  function getRouteLine(from: number, to: number) {
    return buildRouteLine(points, trip?.legs ?? [], from, to)
  }

  /**
   * Draws the route through the first `pointCount` points, plus the part of the next
   * stretch `walked` so far, if it is being drawn.
   */
  function setRoute(pointCount: number, walked?: ReturnType<typeof walkLine>) {
    const from = points[pointCount - 1]

    routeTip = walked?.passed.at(-1)

    layers.setRoute(
      pointCount,
      () => getRouteLine(0, pointCount),
      walked && from
        ? {
            from: from.coordinates,
            mode: getTravelMode(points, trip?.legModes ?? [], walked.start, walked.end),
            passed: walked.passed,
          }
        : undefined,
    )
  }

  function updateStops() {
    const slide = slides[index]
    const currentStopIndex = slide?.type === 'stop' ? slide.stopIndex : -1

    layers.setStops(
      points.map((point, pointIndex) => {
        const state: StopState =
          pointIndex === currentWaypointPoint ||
          (point.stopIndex === currentStopIndex && !point.waypoint && !traveling)
            ? 'current'
            : pointIndex < drawnPointCount
              ? 'visited'
              : 'upcoming'

        return { coordinates: point.coordinates, state, waypoint: point.waypoint }
      }),
    )
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
    const leg = measureLine(getRouteLine(legStart, legEnd))

    if (leg.total === 0 || reducedMotion.matches) {
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

      // Sets off and arrives gently, as the onward drive on each card does.
      setRoute(legStart + 1, walkLine(leg, easeInOut(t)))
      legFrame = requestAnimationFrame(step)
    }

    legFrame = requestAnimationFrame(step)
  }

  function frameTrip(options: { instant?: boolean } = {}) {
    camera.overview(
      points.map((point) => point.coordinates),
      STOP_ZOOM,
      options,
    )
  }

  /** Frames where the day sets off from and every place it visits. */
  /**
   * Stays where the day sets off (where the night was spent, or the last waypoint
   * passed the evening before) and eases to a zoom for the day's first drive.
   */
  function frameDay(slide: Extract<Slide, { type: 'day' }>) {
    const stop = trip?.stops[slide.stopIndex]

    if (!stop) {
      return
    }

    const [first, count] = getWaypointsBefore(slide)
    const start = getStopPointStart(slide.stopIndex)
    // A travel day drives through its own waypoints; any other day on to its first stop.
    const end = slide.waypointsOnly
      ? start + stop.waypoints.findLastIndex((waypoint) => waypoint.day === slide.day)
      : getStopPlaceStart(slide.stopIndex)

    camera.follow(getRouteLine(Math.max(first + count - 1, 0), end + 1), NEARBY_MAX_ZOOM - 1)
  }

  /**
   * Follows the drive on a stop's card: the drive here while it is still being
   * drawn from `arrivingFrom`, else the drive on to the next place.
   */
  function frameStop(stopIndex: number, arrivingFrom?: number) {
    const placeStart = getStopPlaceStart(stopIndex)
    const onwardEnd = Math.min(stopPointEnds[stopIndex]!, points.length - 1)
    const stretch =
      arrivingFrom === undefined
        ? getRouteLine(placeStart, onwardEnd + 1)
        : getRouteLine(arrivingFrom, placeStart + 1)
    const spread = stretch.some(
      ([longitude, latitude]) => longitude !== stretch[0]![0] || latitude !== stretch[0]![1],
    )

    // A place with nowhere to drive on to is shown at a city's scale.
    camera.follow(stretch, spread ? NEARBY_MAX_ZOOM : STOP_ZOOM)
  }

  function moveCamera(arrivingFrom?: number) {
    const slide = slides[index]

    if (!slide) {
      return
    }

    mapLabels.clear()

    if (slide.type === 'day') {
      frameDay(slide)
      return
    }

    if (slide.type !== 'stop') {
      frameTrip()
      return
    }

    // The first place carries the day and time; the card itself shows no title. The
    // recording header already shows the day, so its labels only add the time.
    showLabels(
      slide.stop.places.map((place, placeIndex) =>
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
    frameStop(slide.stopIndex, arrivingFrom)
  }

  function showLabels(labels: MapLabel[]) {
    // On wide screens the card sits on the left, so labels open to the right.
    mapLabels.show(labels, { alignLeft: !isNarrow() && !isStageLayout() })
  }

  /**
   * Flies through `points[first..first + count)` one by one, drawing the route and
   * pausing at each, then calls `arrive`. Gives up when another slide takes over.
   */
  async function travelThroughWaypoints(first: number, count: number, arrive: () => void) {
    const token = travelToken

    traveling = true

    if (isStageLayout()) {
      clearStagePhotos()
    }

    for (let pointIndex = first; pointIndex < first + count; pointIndex += 1) {
      const point = points[pointIndex]!

      currentWaypointPoint = pointIndex
      updateStops()
      showLabels([
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
      camera.follow(getRouteLine(Math.max(pointIndex - 1, 0), pointIndex + 1), STOP_ZOOM)
      await Promise.all([
        drawnPointCount >= pointIndex + 1
          ? Promise.resolve()
          : drawLeg(Math.max(pointIndex - 1, 0), pointIndex + 1),
        new Promise((resolve) => setTimeout(resolve, reducedMotion.matches ? 0 : getLegDuration())),
      ])

      if (token !== travelToken) {
        return
      }

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
      // A copy of the old photo stays put underneath while the new one fades in.
      outgoing.style.cssText = `position:absolute;top:0;left:0;width:${photo.offsetWidth}px;height:${photo.offsetHeight}px;max-width:none;max-height:none;pointer-events:none`
      frame.insertBefore(outgoing, photo)
    }

    update()

    if (!frame || !from || reducedMotion.matches) {
      return
    }

    if (outgoing && photo) {
      const shift = Math.sign(direction) * PHOTO_SLIDE_DISTANCE

      // The old photo stays fully visible under the new one, and only fades in the
      // second half, where a differently shaped new photo doesn't cover it. Cancelled
      // when photos change again quickly; the copy goes either way.
      void outgoing
        .animate([{ opacity: 1 }, { offset: 0.5, opacity: 1 }, { opacity: 0 }], PHOTO_SWITCH)
        .finished.catch(() => {})
        .finally(() => outgoing.remove())
      photo.animate(
        [
          { opacity: 0, transform: `translateX(${shift}px)` },
          { opacity: 1, transform: 'none' },
        ],
        PHOTO_SWITCH,
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
      PHOTO_SWITCH,
    )
  }

  /** Plays a live photo's motion once over its still, then fades back to the still. */
  function playLivePhoto(image: TripImage) {
    const frame = photo?.parentElement

    liveVideo?.remove()
    liveVideo = undefined

    // The stage card plays its own copy, see showStagePhoto.
    if (
      !image.video ||
      !frame ||
      reducedMotion.matches ||
      isStageLayout() ||
      !canAutoplayLiveClips()
    ) {
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

  /** Shows one of a stop's photos, moving the card's timer to where it is shown. */
  function selectPhoto(stop: TripStop, imageIndex: number) {
    const duration = getSlideDuration(slides[index]) ?? 0

    elapsed = (duration / stop.images.length) * imageIndex
    animatePhotoSize(() => showPhoto(stop, imageIndex), imageIndex - photoIndex)
    renderProgress()
  }

  /** Steps through the current stop's photos, for ↑ and ↓ and a swipe on the photo. */
  function stepPhoto(direction: number) {
    const slide = slides[index]

    if (slide?.type === 'stop' && slide.stop.images.length > 1) {
      selectPhoto(
        slide.stop,
        Math.min(Math.max(photoIndex + direction, 0), slide.stop.images.length - 1),
      )
    }
  }

  /**
   * Swiping the photo sideways moves to the next or previous one. The photo is also a
   * link to the day's page, so a swipe swallows the click it ends with; a tap still
   * opens the page. Vertical drags are left to scroll the card.
   */
  function addPhotoSwipe(link: HTMLElement) {
    let start: { x: number; y: number } | undefined
    let swiped = false

    link.style.touchAction = 'pan-y'
    link.addEventListener(
      'pointerdown',
      (event) => {
        start = { x: event.clientX, y: event.clientY }
        swiped = false
      },
      { signal },
    )
    link.addEventListener(
      'pointerup',
      (event) => {
        const dx = event.clientX - (start?.x ?? event.clientX)
        const dy = event.clientY - (start?.y ?? event.clientY)

        start = undefined

        if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.5) {
          swiped = true
          stepPhoto(dx < 0 ? 1 : -1)
        }
      },
      { signal },
    )
    link.addEventListener(
      'click',
      (event) => {
        if (swiped) {
          event.preventDefault()
          swiped = false
        }
      },
      { signal },
    )
    // A dragged link would otherwise start a native drag of the image.
    link.addEventListener('dragstart', (event) => event.preventDefault(), { signal })
  }

  /** Jumps to the next day's opener, or back to this day's (then the one before). */
  function stepDay(direction: number) {
    const openers = slides.flatMap((slide, slideIndex) =>
      slide.type === 'day' ? [slideIndex] : [],
    )
    const target =
      direction > 0
        ? openers.find((slideIndex) => slideIndex > index)
        : openers.findLast((slideIndex) => slideIndex < index)

    goTo(target ?? (direction > 0 ? slides.length - 1 : 0))
  }

  function setSpeed(next: number) {
    speed = next
    writeSpeedPreference(speed)
    renderSpeedButton()
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
      button.addEventListener('click', () => selectPhoto(stop, imageIndex), { signal })
      return button
    })
    list.append(...thumbnails)
    return list
  }

  function renderPlayButton() {
    const icon = playButton.firstElementChild

    playButton.setAttribute('aria-label', playing ? '暂停' : '播放')
    playButton.title = `${playing ? '暂停' : '播放'}（空格）`
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
      ambient = { ...music, src }
      fadeMusicIn(music)
      syncAmbientMusic()
    })
  }

  function stopAmbientMusic() {
    pendingAmbient = undefined

    if (ambient) {
      closeMusic(ambient)
      ambient = undefined
    }
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
      eyebrow.textContent = ''
      title.textContent = trip.title
      body.replaceChildren(
        createElement('p', 'shrink-0 text-tx-2', getTripMeta(trip)),
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
          'relative block h-auto max-h-[min(40dvh,22rem)] w-auto max-w-full @max-[48rem]:max-h-[38dvh]'
        photo.sizes = IMAGE_SIZES
        photo.decoding = 'async'
        link.append(photo)

        if (stop.images.length > 1) {
          addPhotoSwipe(link)
        }
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
    } else {
      const stats = document.createElement('dl')
      const replay = createElement(
        'button',
        'mt-3 inline-flex min-h-9 shrink-0 cursor-pointer self-start items-center gap-1.5 rounded-full border border-border bg-transparent px-3.5 text-[0.8125rem] text-tx hover:bg-inline',
        '重新播放',
      ) as HTMLButtonElement
      const replayIcon = createElement('span', 'i-lucide-rotate-ccw', '')

      eyebrow.textContent = ''
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

    preloadNextStop()
    renderStage(slide)
    renderProgress()
    renderPlayButton()
  }

  /**
   * Starts loading the next stop's photos and thumbnails while the current card is up,
   * whatever it is, so the next card opens with its pictures already there.
   */
  function preloadNextStop() {
    const next = slides.slice(index + 1).find((slide) => slide.type === 'stop')

    for (const image of next?.type === 'stop' ? next.stop.images : []) {
      if (!preloaded.has(image.src)) {
        preloaded.add(image.src)
        preloadImage(image)
        preloadThumbnail(image)
      }
    }
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
      stageCenter.replaceChildren(createIntroCard(trip, covers))
      return
    }

    if (slide.type === 'summary') {
      stageCenter.replaceChildren(createSummaryCard(trip))
      return
    }

    if (slide.type === 'day') {
      stageCenter.replaceChildren(createDayCard(trip, slide))
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

    // The new photo fades in over the old ones, which only fade once it is half in.
    for (const old of frame.querySelectorAll<HTMLElement>('img, video')) {
      old.style.transitionDelay = '300ms'
      old.style.opacity = '0'
      setTimeout(() => old.remove(), reducedMotion.matches ? 0 : 900)
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
    if (image.video && !reducedMotion.matches && canAutoplayLiveClips()) {
      const video = createLiveVideo(image.video, '')

      video.style.width = img.style.width
      video.style.height = img.style.height
      frame.append(video)
      void video.play().catch(() => video.remove())
    }
  }

  /** Notes when a day or the summary starts in the recording. */
  function addChapter(slide: Slide) {
    if (recording && (slide.type === 'day' || slide.type === 'summary')) {
      recording.chapters.add(
        slide.type === 'day' ? `第 ${slide.day} 天 · ${slide.date}` : '旅程回顾',
        Math.floor((performance.now() - recording.start) / 1000),
      )
    }
  }

  /**
   * The waypoints the map passes before a slide shows, as `[first point, count]`.
   * Waypoints from the day before a stop (leaving the evening before) are passed
   * before that day's opener, so the new day starts where it really started.
   */
  function getWaypointsBefore(slide: Slide) {
    return getWaypointRange(
      slide,
      trip?.stops ?? [],
      stopPointEnds,
      trip?.trailingWaypoints.length ?? 0,
    )
  }

  /** The slide a shared link names: a stop's card, or its day's opener when it starts the day. */
  function getHashSlideIndex() {
    const reloaded = reloadPending
    const anchor = decodeURIComponent(location.hash.slice(1))

    reloadPending = false

    if (reloaded) {
      return 0
    }

    const stopIndex = trip?.stops.findIndex((stop) => stop.anchor === anchor) ?? -1

    if (!anchor || stopIndex === -1) {
      return 0
    }

    // A travel day before the stop isn't what the link names; its own day is.
    return slides.findIndex(
      (slide) =>
        ((slide.type === 'day' && !slide.waypointsOnly) || slide.type === 'stop') &&
        slide.stopIndex === stopIndex,
    )
  }

  /** Keeps the address bar pointing at the current card, so it can be shared. */
  function syncHash(slide: Slide) {
    const anchor =
      slide.type === 'stop' || (slide.type === 'day' && !slide.waypointsOnly)
        ? trip?.stops[slide.stopIndex]?.anchor
        : ''
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

    if (staging?.recorder) {
      preparePhotosAhead()
    }

    // Autoplay stays quiet, like a carousel; stepping through by hand names each card.
    announcer.textContent = playing ? '' : getSlideLabel(slide)

    if (!getSlideDuration(slide)) {
      playing = false
    }

    renderSlide()

    routeProgress = undefined
    // Where a drive to this stop starts, while it is still to be drawn.
    let arrivingFrom: number | undefined
    let arrival: Promise<void> | undefined

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
        arrivingFrom = legStart ?? Math.max(placeStart - 1, 0)
        arrival = drawLeg(arrivingFrom, arriveAt)
      }
    } else if (slide.type === 'day') {
      // Usually the previous stop's route already reached here; jumps set it directly.
      if (legStart === -1 || drawnPointCount === 0) {
        cancelLeg()
        // Drawn through the waypoints passed before this card, or to the first point.
        const [first, count] = getWaypointsBefore(slide)
        drawnPointCount = Math.min(Math.max(first + count, 1), points.length)
        setRoute(drawnPointCount)
      }
    } else {
      cancelLeg()
      drawnPointCount = slide.type === 'intro' ? 0 : points.length
      setRoute(drawnPointCount)
    }

    updateStops()

    moveCamera(arrivingFrom)

    if (arrival && slide.type === 'stop') {
      // Once the drive here is drawn, the camera eases to the zoom of the drive on.
      const token = travelToken
      void arrival.then(() => {
        if (token === travelToken && index === nextIndex) {
          frameStop(slide.stopIndex)
        }
      })
    }

    renderRouteProgress()

    if (staging && slide.type === 'summary') {
      if (staging.music) {
        fadeMusicOut(staging.music, EXPORT_SUMMARY_HOLD / 1000)
      }

      staging.stopTimer = setTimeout(stopStage, EXPORT_SUMMARY_HOLD)
    }
  }

  /**
   * Shows the presentation's exit button and pointer, then hides both again once the
   * pointer has rested for a while, so they don't sit on the picture.
   */
  function wakeControls() {
    clearTimeout(idleTimer)
    page.removeAttribute('data-trip-idle')

    if (page.hasAttribute('data-trip-presenting')) {
      idleTimer = setTimeout(() => page.setAttribute('data-trip-idle', ''), CONTROLS_IDLE)
    }
  }

  /** The keyboard shortcuts; the shortcut panel (press ?) lists them. */
  function handleShortcut(event: KeyboardEvent) {
    const target = event.target instanceof HTMLElement ? event.target : undefined

    // Leaves the browser's own shortcuts (⌘R, Ctrl+F…) and typing alone.
    if (
      !trip ||
      event.defaultPrevented ||
      event.metaKey ||
      event.ctrlKey ||
      event.altKey ||
      target?.closest('input, textarea, select, [contenteditable]')
    ) {
      return
    }

    // Esc closes the presenting/recording settings, and only them.
    if (!exportOptions.hidden && event.key === 'Escape') {
      event.preventDefault()
      setExportOptionsOpen(false)
      return
    }

    // Esc or ? closes the panel, and only the panel: the trip stays open.
    if (shortcutsDialog.open) {
      if (event.key === '?' || event.key === 'Escape') {
        event.preventDefault()
        shortcutsDialog.close()
      }

      return
    }

    // Also while a presentation or recording is still starting up.
    if ((staging || stageToken) && event.key === 'Escape') {
      event.preventDefault()
      stopStage()
      return
    }

    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key
    const actions: Record<string, () => void> = {
      ' ': () => playButton.click(),
      '-': () => setSpeed(SPEEDS.findLast((value) => value < speed) ?? speed),
      '=': () => setSpeed(SPEEDS.find((value) => value > speed) ?? speed),
      '+': () => setSpeed(SPEEDS.find((value) => value > speed) ?? speed),
      '?': () => shortcutsDialog.showModal(),
      ArrowDown: () => stepPhoto(1),
      ArrowLeft: () => (event.shiftKey ? stepDay(-1) : goTo(index - 1)),
      ArrowRight: () => (event.shiftKey ? stepDay(1) : goTo(index + 1)),
      ArrowUp: () => stepPhoto(-1),
      End: () => goTo(slides.length - 1),
      Home: () => goTo(0),
      f: () => {
        if (!staging) {
          void startStage(innerWidth >= innerHeight ? 'landscape' : 'portrait', 'present')
        }
      },
      k: () => playButton.click(),
      m: () => musicButton.click(),
      r: () => {
        if (!staging && !exportButton.hidden) {
          setExportOptionsOpen(true, 'record')
        }
      },
    }
    const action = actions[key]

    // A focused button or link keeps Space for itself.
    if (action && !(key === ' ' && target?.closest('button, a'))) {
      event.preventDefault()
      action()
    }
  }

  /** Opens the settings for presenting or recording in place of the footer buttons. */
  function setExportOptionsOpen(open: boolean, mode: StageMode = stageMode) {
    stageMode = mode
    setupTitle.textContent = mode === 'present' ? '放映' : '导出视频'
    startButton.textContent = mode === 'present' ? '开始放映' : '开始录制'
    renderSetup()
    exportOptions.hidden = !open
    controls.hidden = open
    actions.hidden = open

    if (open) {
      startButton.focus({ preventScroll: true })
    }
  }

  function renderSetup() {
    for (const button of ratioButtons) {
      button.setAttribute(
        'aria-checked',
        String(button.dataset.calendarMapTripExportRatio === ratio),
      )
    }

    safeAreaInput.checked = safeArea
    // Margins for a short-video app's overlays only matter for a portrait video.
    safeAreaRow.hidden = stageMode !== 'record' || ratio !== 'portrait'
  }

  /**
   * Plays the trip once from the start in the story layout. Recording captures the
   * tab, cropped to the stage, and downloads the video; presenting goes full screen.
   */
  async function startStage(ratio: ExportRatio, mode: StageMode) {
    const currentTrip = trip

    setExportOptionsOpen(false)

    if (!currentTrip || staging || stageToken) {
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
    page.toggleAttribute(
      'data-trip-safe-area',
      safeArea && mode === 'record' && ratio === 'portrait',
    )
    wakeControls()
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

    // Stopped while the music loaded: let go of what was opened for it.
    if (stageToken !== token) {
      if (music) {
        closeMusic(music)
      }

      for (const streamTrack of stream?.getTracks() ?? []) {
        streamTrack.stop()
      }

      return
    }

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
        const chapters = recording?.chapters.toString()

        finishStage()
        downloadBlob(new Blob(chunks, { type }), `${name}.${type.includes('mp4') ? 'mp4' : 'webm'}`)

        if (chapters) {
          downloadBlob(new Blob([chapters], { type: 'text/plain' }), `${name}-章节.txt`)
        }
      })
      track?.addEventListener('ended', stopStage)
      recorder = activeRecorder
    }

    staging = {
      mode,
      ...(music ? { music } : {}),
      ...(recorder ? { recorder } : {}),
      ...(stream ? { stream } : {}),
    }

    // Reset to a still intro first: the camera jumps to the whole route and the
    // tiles load before the first frame, so playback doesn't open mid-flight.
    goTo(0, { play: false })
    frameTrip({ instant: true })
    await waitForMapIdle()

    if (stageToken !== token) {
      return
    }

    recorder?.start(1000)

    if (recorder) {
      recording = { chapters: createChapterLog(currentTrip.title), start: performance.now() }
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

  function stopStage() {
    if (staging?.recorder?.state === 'recording') {
      staging.recorder.stop()
    } else {
      finishStage()
    }
  }

  function finishStage() {
    // Still starting (loading its music), the stage has nothing of its own to release
    // yet, but its layout is already up.
    const starting = stageToken !== undefined && !staging

    stageToken = undefined
    recording = undefined

    if (!staging && !starting) {
      return
    }

    const presenting = page.hasAttribute('data-trip-presenting')

    if (staging) {
      clearTimeout(staging.stopTimer)

      for (const track of staging.stream?.getTracks() ?? []) {
        track.stop()
      }

      if (staging.music) {
        closeMusic(staging.music)
      }
    }

    staging = undefined
    delete page.dataset.exportRatio
    page.removeAttribute('data-trip-safe-area')
    page.removeAttribute('data-trip-presenting')
    clearTimeout(idleTimer)
    page.removeAttribute('data-trip-idle')

    if (presenting && document.fullscreenElement) {
      void document.exitFullscreen().catch(() => {})
    }

    getMap()?.resize()
    // Back to the side card for whatever slide is showing.
    renderSlide()
  }

  /** Starts decoding the photos of the first stop after the current slide. */
  function preparePhotosAhead() {
    const next = slides.slice(index + 1).find((slide) => slide.type === 'stop')

    for (const image of next?.type === 'stop' ? next.stop.images : []) {
      if (!decodedPhotos.has(image.src)) {
        const photoImage = new Image()

        photoImage.src = image.src
        decodedPhotos.set(
          image.src,
          photoImage
            .decode()
            .catch(() => {})
            .then(() => {
              readyPhotos.add(image.src)
            }),
        )
      }
    }
  }

  /** Whether the next slide can show: a stop's first photo has to be ready to record. */
  function isNextSlideReady() {
    const next = slides[index + 1]
    const first = next?.type === 'stop' ? next.stop.images[0] : undefined

    if (!staging?.recorder || !first || readyPhotos.has(first.src)) {
      return true
    }

    preparePhotosAhead()
    return false
  }

  function tick(now: number) {
    frame = requestAnimationFrame(tick)
    camera.update(now)

    const delta = lastFrameTime === undefined ? 0 : now - lastFrameTime
    const duration = getSlideDuration(slides[index])

    lastFrameTime = now

    // The card's timer waits while the map travels between waypoints or the mouse rests on it.
    if (!playing || !duration || document.hidden || traveling || (hovering && !staging)) {
      return
    }

    elapsed += delta * speed
    renderProgress()

    // A slow photo holds the current card a little longer rather than record a blank.
    if (elapsed >= duration && isNextSlideReady()) {
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
    mapLabels.destroy()
    layers.remove()
    camera.stop()
    routeTip = undefined
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
  page.addEventListener('pointermove', wakeControls, { signal })
  page.addEventListener('pointerdown', wakeControls, { signal })
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
  safeAreaInput.addEventListener(
    'change',
    () => {
      safeArea = safeAreaInput.checked
      writeSafeAreaPreference(safeArea)
    },
    { signal },
  )
  renderSpeedButton()
  speedButton.addEventListener('click', () => setSpeed(getNextSpeed(speed)), { signal })
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

  for (const button of ratioButtons) {
    button.addEventListener(
      'click',
      () => {
        ratio = button.dataset.calendarMapTripExportRatio as ExportRatio
        writeRatioPreference(ratio)
        renderSetup()
      },
      { signal },
    )
  }
  startButton.addEventListener('click', () => void startStage(ratio, stageMode), { signal })
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
  shortcutsDialog.addEventListener(
    'click',
    (event) => {
      // A click on the backdrop, outside the panel, closes it.
      if (event.target === shortcutsDialog) {
        shortcutsDialog.close()
      }
    },
    { signal },
  )
  window.addEventListener('keydown', handleShortcut, { signal })
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
