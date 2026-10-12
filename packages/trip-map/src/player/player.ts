import type { Map as MapboxMap } from 'mapbox-gl'
import { getDistanceKm, type Coordinates } from '../geo.ts'
import { createCamera } from './camera.ts'
import {
  createCountUp,
  createElement,
  createLiveVideo,
  createVideoPlayer,
  createMosaic,
  createWeather,
  downloadBlob,
  IMAGE_SIZES,
  parseJson,
  perFrame,
  preloadImage,
  preloadThumbnail,
  queryRequired,
} from './dom.ts'
import { createChapterLog, getShortPlaceName, getSummaryStats } from './labels.ts'
import {
  closeMusic,
  fadeMusicIn,
  fadeMusicOut,
  loadMusic,
  setMusicAudible,
  type Music,
} from './music.ts'
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
} from './preferences.ts'
import {
  easeInOut,
  getRouteLine as buildRouteLine,
  getTravelMode,
  measureLine,
  walkLine,
  type MeasuredLine,
} from './route.ts'
import { createMapLabels } from './map-labels.ts'
import {
  canRecord,
  createRecording,
  cropTo,
  releaseCapture,
  requestTabCapture,
  type Recording,
} from './recorder.ts'
import { createTransition, easeCardChange, easePhotoChange, reducedMotion } from './motion.ts'
import { createStagePhotoCard } from './stage-photo-card.ts'
import { createScreenWakeLock } from './wake-lock.ts'
import { createRouteLayers, type StopState } from './route-layers.ts'
import {
  buildSlides,
  getPhotoAt,
  getPhotoStarts,
  getSlideDuration,
  getSlideLabel,
  getClock,
  getStopEyebrow,
  getStopPointEnds,
  getStopPointStart as getPointStart,
  getWaypointsBefore as getWaypointRange,
  type SlideTiming,
} from './slides.ts'
import { createDayHeading } from './day-heading.ts'
import { createIntroCard, createSummaryCard } from './stage-cards.ts'
import { getIntroCovers, getTripMeta } from './intro.ts'
import type {
  MapLabel,
  Slide,
  TripData,
  TripImage,
  TripPlace,
  TripPoint,
  TripDataStop,
  TripSummary,
  TripDataWaypoint,
} from './types.ts'

// Plays a trip back on the map: the route draws itself from place to place while a
// card shows each stop's photos and words. The same playback can run in a story
// layout to present full screen or to record as a video.

type TripPlayerOptions = {
  getMap: () => MapboxMap | undefined
  /** Where a trip's playback data (`TripData` as JSON) is fetched from. */
  getTripDataUrl: (id: string) => string
  onClose: () => void
  page: HTMLElement
  signal: AbortSignal
  /** Shown on the summary card of a video: where to find the trips, like `hyoban.cc/trip/`. */
  siteLine: string
}

// Long enough to read a waypoint's name before driving on.
const WAYPOINT_PAUSE = 1000
const LEG_DURATION = 2500
// Drawing a drive takes from SHORT_LEG for a hop across town up to LEG_DURATION for
// LONG_LEG_KM or more, so nearby places don't keep the viewer waiting. Even a short
// hop leaves the camera, which eases over most of a second, time to follow it.
const SHORT_LEG = 1500
const LONG_LEG_KM = 400
const STOP_ZOOM = 9
// The closest the camera comes in while following a short drive.
const NEARBY_MAX_ZOOM = 13
// How long the summary card stays in an exported video before it ends.
const EXPORT_SUMMARY_HOLD = 4000
// Presenting or recording, a stop's photo card pops up this soon when the route is
// already there; otherwise once the drive there is drawn.
const CARD_POP = 400
// How long the pointer rests before the presentation's exit button fades away.
const CONTROLS_IDLE = 2500
// The address bar follows the playing card so it can be shared, but reloading the page
// should start the trip over rather than resume mid-way. Only the first trip started
// after a reload ignores the card; links followed later in the same page still work.
let reloadPending =
  (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)
    ?.type === 'reload'

// The side card slides in from just below as a trip opens, and back down as it closes.
const PANEL_HIDDEN: Keyframe[] = [
  { opacity: 1, transform: 'none' },
  { opacity: 0, transform: 'translateY(0.75rem) scale(0.98)' },
]
const PANEL_MOTION: KeyframeAnimationOptions = {
  duration: 280,
  easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
}

type ExportRatio = 'landscape' | 'portrait'
// The story layout either plays on screen or is recorded to a video file.
type StageMode = 'present' | 'record'

export function createTripPlayer({
  getMap,
  getTripDataUrl,
  onClose,
  page,
  signal,
  siteLine,
}: TripPlayerOptions) {
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
  const stageHeading = createDayHeading(stageEyebrow, stageTitle)
  const stagePhotos = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-stage-photos]')
  const stagePhotoCard = createStagePhotoCard(stagePhotos)
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
  // Bumped whenever the stage photo card's pending pop-up is called off.
  let stagePhotoToken = 0
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
  const stageMusicButton = queryRequired<HTMLButtonElement>(
    page,
    '[data-calendar-map-trip-stage-music]',
  )
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
        recording?: Recording
        stopTimer?: ReturnType<typeof setTimeout>
      }
    | undefined
  let stageMode: StageMode = 'record'
  // The stage running or starting, and the speed to go back to after a recording,
  // which always plays at 1×.
  let activeStage: { mode: StageMode; speed: number } | undefined
  // Where each day starts in the video being recorded, for the description's chapters.
  let chapters: { log: ReturnType<typeof createChapterLog>; start: number } | undefined
  // Music for the ordinary card playback, switched on with the music button.
  let ambient: (Music & { src: string }) | undefined
  let musicOn = readMusicPreference()
  let pendingAmbient: { src: string } | undefined
  let stageToken: object | undefined
  // The camera and the labels each read the layout every frame. Whichever runs first in
  // a frame measures, before anything is written, and the other reuses it; reading it
  // again after the writes would make the browser lay out a second time.
  const getFrameLayout = perFrame(readLayout)
  const mapLabels = createMapLabels({
    getLayout: getFrameLayout,
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

  /**
   * The map's box and the cards and controls over it, in viewport pixels, and the
   * camera's padding for them.
   */
  function readLayout() {
    return {
      // Labels keep clear of the clock beside the car.
      avoid:
        !isStageLayout() && headClock.getClientRects().length > 0
          ? [headClock.getBoundingClientRect()]
          : [],
      blocked: readBlocked(),
      container: getMap()?.getContainer().getBoundingClientRect(),
      padding: getCameraPadding(),
    }
  }

  function readBlocked() {
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
  }

  let trip: TripData | undefined
  // Photos for the intro and summary.
  let covers: TripImage[] = []
  // Every place of every stop in visiting order; the route runs through these.
  let points: TripPoint[] = []
  // Index into `points` just past each stop's last place.
  let stopPointEnds: number[] = []
  let slides: Slide[] = []
  let index = 0
  // How far into the current card playback is. Picking a photo by hand moves it to
  // that photo's time.
  let elapsed = 0
  // The same, but only ever moved on by playback: the route's drive on a card follows
  // it, so picking photos by hand never moves the map back and forth.
  let driveElapsed = 0
  let playing = false
  // Paused by hand: the map's own motion (the drive being drawn, the camera, the
  // flight past waypoints) holds still too, until playback resumes or the card changes.
  let held = false
  // Milliseconds of motion so far; it stands still while held.
  let motionTime = 0
  let speed: number = readSpeedPreference()
  // The speed's digits roll from one to the next.
  const speedFlow = document.createElement('number-flow')

  speedFlow.numberSuffix = '×'
  speedButton.replaceChildren(speedFlow)
  const camera = createCamera({
    // The tip while the route is drawn, else the last place it reached.
    getFocus: () => routeTip ?? points[Math.max(drawnPointCount - 1, 0)]?.coordinates,
    getMap,
    getPadding: () => getFrameLayout().padding,
    getSpeed: () => speed,
    reducedMotion,
  })
  // On the map, the day and time ride beside the car, ship or plane.
  const headClock = createElement('div', 'calendar-map-trip-head__clock', '')
  const headClockDate = createElement('p', 'calendar-map-trip-head__date', '')
  const headClockTime = createElement('p', 'calendar-map-trip-head__time', '')
  const headHeading = createDayHeading(headClockDate, headClockTime)

  headClock.append(headClockDate, headClockTime)
  const layers = createRouteLayers(
    getMap,
    () => ({
      ink: getComputedStyle(page).getPropertyValue('--color-tx').trim() || '#21201c',
      paper: getComputedStyle(page).getPropertyValue('--color-bg').trim() || '#fdfdfc',
    }),
    headClock,
  )
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
  // The stretch of route being drawn now: from `points[from]` toward `points[to]`,
  // `fraction` of the way along `leg`. A card that comes up before it has arrived
  // carries on from there rather than drawing it again.
  let partial: { fraction: number; from: number; leg: MeasuredLine; to: number } | undefined
  let photoIndex = 0
  let photo: HTMLImageElement | undefined
  let thumbnails: HTMLButtonElement[] = []
  // The side card's change to the next card, and to the next photo, in progress.
  const cardTransition = createTransition()
  const photoTransition = createTransition()
  let liveVideo: HTMLVideoElement | undefined
  // How long the current stop waits for the route to arrive before its photos' time
  // starts, in the card's own time.
  let photoDelay = 0
  // A mouse resting on the card holds the current slide so it can be read.
  let hovering = false
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  // Photos already asked for, so going back and forth doesn't ask again.
  const preloaded = new Set<string>()
  // Photos decoded ahead while recording, so a card never opens on a blank frame.
  const decodedPhotos = new Map<string, Promise<void>>()
  const readyPhotos = new Set<string>()
  // The screen stays on through a presentation or a recording.
  const wakeLock = createScreenWakeLock(signal)

  /**
   * A stop's photos are timed from when the route reaches it (presenting or recording,
   * that's also when its photo card appears, see renderStage), so the stop lasts that
   * much longer and the first and last photos get as long as the others.
   */
  function getPhotoDelay(slide: Slide | undefined) {
    return slide === slides[index] && slide?.type === 'stop' ? photoDelay : 0
  }

  /** Live photos get time for their clips, which play unless motion is reduced. */
  function getTiming(): SlideTiming {
    return { liveClips: !reducedMotion.matches }
  }

  /** How long a slide stays up, in the card's own time (which runs faster at 2×). */
  function getDuration(slide: Slide | undefined) {
    const duration = getSlideDuration(slide, getTiming())
    return duration === undefined ? undefined : duration + getPhotoDelay(slide)
  }

  // The page itself becomes a narrow stage while recording or presenting, so measure it.
  function isNarrow() {
    return page.clientWidth <= 768
  }

  /** Presenting or recording, a story-style overlay replaces the side card. */
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
    const duration = getDuration(slide)

    if (slide?.type !== 'stop' || !duration || traveling || legFrame !== undefined) {
      return
    }

    const from = getStopPlaceStart(slide.stopIndex)
    const to = Math.min(stopPointEnds[slide.stopIndex]!, points.length - 1)

    // The drive here hasn't been drawn yet (the card has only just come up).
    if (drawnPointCount < from + 1) {
      return
    }

    if (routeProgress?.stopIndex !== slide.stopIndex) {
      routeProgress = {
        ...measureLine(getRouteLine(from, to + 1)),
        startedAt: driveElapsed,
        stopIndex: slide.stopIndex,
      }
    }

    // Starts from rest once any drive here is done, and arrives as the card ends.
    const left = duration - routeProgress.startedAt
    const fraction = left > 0 ? Math.min((driveElapsed - routeProgress.startedAt) / left, 1) : 1

    if (fraction >= 1 || routeProgress.total === 0) {
      drawnPointCount = fraction >= 1 ? to + 1 : from + 1
      setRoute(drawnPointCount)
      return
    }

    drawnPointCount = from + 1
    setRoute(from + 1, { fraction: easeInOut(fraction), leg: routeProgress, to })
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
    // The trip has its own arrow, +/- and Home/End keys; the map's would move the camera too.
    if (trip) {
      getMap()?.keyboard.disable()
    }

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
   * stretch drawn so far, if one is being drawn: `fraction` of `leg`, which heads
   * for `points[to]`.
   */
  function setRoute(
    pointCount: number,
    piece?: { fraction: number; leg: MeasuredLine; to: number },
  ) {
    const leg = piece?.leg
    const walked = piece && walkLine(piece.leg, piece.fraction)

    partial = piece && { ...piece, from: pointCount - 1 }
    routeTip = walked?.passed.at(-1)
    // The clock ticks on while the route is drawn, and rolls when it jumps.
    renderClock({ animate: !piece })

    layers.setRoute(
      pointCount,
      () => getRouteLine(0, pointCount),
      leg && walked
        ? {
            line: leg.line,
            mode: getTravelMode(
              points,
              trip?.legs ?? [],
              trip?.legModes ?? [],
              walked.start,
              walked.end,
            ),
            ratio: walked.ratio,
            segment: walked.segment,
          }
        : undefined,
    )
  }

  /** The day and time the route's tip has reached. */
  function getTipClock() {
    return partial
      ? getClock(points, partial.from, partial.to, partial.fraction)
      : getClock(points, Math.max(drawnPointCount - 1, 0))
  }

  /**
   * Brings the clock up to the route's tip: the one beside the car on the map, or the
   * story layout's heading, which shows it the whole trip through.
   */
  function renderClock(options: { animate: boolean }) {
    if (points.length === 0) {
      return
    }

    ;(isStageLayout() ? stageHeading : headHeading).set(getTipClock(), options)
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

  /**
   * How the route gets to `points[legEnd - 1]`: on from where the tip is, if the
   * stretch being drawn heads there, or else from `points[legStart]`.
   */
  function planLeg(legStart: number, legEnd: number) {
    const to = legEnd - 1

    if (partial && partial.to === to && partial.from <= legStart && partial.fraction < 1) {
      const { fraction, from, leg } = partial

      return {
        duration: getDriveTime(from, legEnd) * (1 - fraction),
        from,
        leg,
        start: fraction,
        to,
      }
    }

    return {
      duration: getDriveTime(legStart, legEnd),
      from: legStart,
      leg: measureLine(getRouteLine(legStart, legEnd)),
      start: 0,
      to,
    }
  }

  function animateLeg(legStart: number, legEnd: number) {
    const { duration, from, leg, start, to } = planLeg(legStart, legEnd)

    if (leg.total === 0 || reducedMotion.matches) {
      drawnPointCount = legEnd
      setRoute(drawnPointCount)
      cancelLeg()
      return
    }

    drawnPointCount = from + 1
    const startTime = motionTime
    // A fresh drive sets off and arrives gently, as the onward drive on each card does;
    // one carried on is already moving, so it only slows to arrive.
    const ease = start > 0 ? (t: number) => 1 - (1 - t) ** 2 : easeInOut
    const step = () => {
      const t = Math.min((motionTime - startTime) / Math.max(duration, 1), 1)

      if (t >= 1) {
        legFrame = undefined
        drawnPointCount = legEnd
        setRoute(drawnPointCount)
        updateStops()
        cancelLeg()
        return
      }

      setRoute(from + 1, { fraction: start + (1 - start) * ease(t), leg, to })
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

    // The heading carries the day and time, so labels are just short names.
    showLabels(
      slide.stop.places.map((place) => ({
        coordinates: [place.longitude, place.latitude],
        name: getShortPlaceName(place.name),
      })),
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
      stagePhotoCard.clear()
    }

    for (let pointIndex = first; pointIndex < first + count; pointIndex += 1) {
      const point = points[pointIndex]!

      currentWaypointPoint = pointIndex
      updateStops()
      showLabels([{ coordinates: point.coordinates, name: getShortPlaceName(point.name) }])
      camera.follow(getRouteLine(Math.max(pointIndex - 1, 0), pointIndex + 1), STOP_ZOOM)
      await Promise.all([
        drawnPointCount >= pointIndex + 1
          ? Promise.resolve()
          : drawLeg(Math.max(pointIndex - 1, 0), pointIndex + 1),
        waitForMotion(
          reducedMotion.matches ? 0 : planLeg(Math.max(pointIndex - 1, 0), pointIndex + 1).duration,
        ),
      ])

      if (token !== travelToken) {
        return
      }

      await waitForMotion(WAYPOINT_PAUSE / speed)

      if (token !== travelToken) {
        return
      }
    }

    // A mouse resting on the card holds it here too, until it moves off.
    while (hovering && !staging) {
      await new Promise((resolve) => setTimeout(resolve, 100))

      if (token !== travelToken) {
        return
      }
    }

    traveling = false
    currentWaypointPoint = -1
    updateStops()
    arrive()
  }

  /** Resolves once `duration` milliseconds of motion have passed, not counting holds. */
  function waitForMotion(duration: number) {
    const until = motionTime + duration

    return new Promise<void>((resolve) => {
      const check = () => {
        if (motionTime >= until) {
          resolve()
        } else {
          requestAnimationFrame(check)
        }
      }

      check()
    })
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
    const duration = getDuration(slides[index])

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
      // The photos share the time from when the card shows them, by their slots.
      const delay = getPhotoDelay(slide)
      const shown = Math.max(elapsed - delay, 0) / Math.max(duration - delay, 1)
      const nextPhotoIndex = getPhotoAt(slide.stop, shown, getTiming())

      if (nextPhotoIndex !== photoIndex) {
        changePhoto(() => showPhoto(slide.stop, nextPhotoIndex))
      }
    }

    renderRouteProgress()
  }

  /** Renders the next card with `update`, easing the side card between the two. */
  function changeCard(update: () => void) {
    if (panel.hidden || reducedMotion.matches || isStageLayout()) {
      cardTransition.end()
      update()
      return
    }

    easeCardChange(cardTransition, { body, header, panel }, update)
  }

  /** Shows the next photo with `update`, fading the side card's photo through. */
  function changePhoto(update: () => void) {
    if (!photo || reducedMotion.matches) {
      photoTransition.end()
      update()
      return
    }

    easePhotoChange(photoTransition, photo, () => liveVideo, update)
  }

  /** Plays a live photo's motion once over its still, then fades back to the still. */
  function playLivePhoto(image: TripImage) {
    const frame = photo?.parentElement

    liveVideo?.remove()
    liveVideo = undefined

    // The stage's photo card plays its own copy.
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

  /**
   * Gives the side card's photo its size up front, from its proportions and the room
   * the card has, so the card's layout is right before the photo has loaded rather
   * than jumping when it arrives.
   */
  function sizePhoto(img: HTMLImageElement, image: TripImage) {
    const frame = img.parentElement
    const box = frame?.parentElement

    if (!frame || !box) {
      return
    }

    const boxStyle = getComputedStyle(box)
    // The card's content width, less the frame's border.
    const available =
      box.clientWidth -
      Number.parseFloat(boxStyle.paddingLeft) -
      Number.parseFloat(boxStyle.paddingRight) -
      (frame.offsetWidth - frame.clientWidth)

    img.style.width = ''
    img.style.height = ''

    const maxHeight = Number.parseFloat(getComputedStyle(img).maxHeight) || Infinity
    const ratio = image.width / image.height
    const width = Math.min(available, maxHeight * ratio)

    img.style.width = `${width}px`
    img.style.height = `${width / ratio}px`
  }

  function showPhoto(stop: TripDataStop, nextPhotoIndex: number) {
    const image = stop.images[nextPhotoIndex]

    if (!photo || !image) {
      return
    }

    photoIndex = nextPhotoIndex

    stagePhotoCard.showPhoto(stop, nextPhotoIndex)

    photo.srcset = image.srcset
    photo.src = image.src
    photo.width = image.width
    photo.height = image.height
    photo.alt = image.alt
    sizePhoto(photo, image)
    playLivePhoto(image)

    for (const [thumbnailIndex, thumbnail] of thumbnails.entries()) {
      if (thumbnailIndex === nextPhotoIndex) {
        thumbnail.setAttribute('aria-current', 'true')
        thumbnail.scrollIntoView({
          behavior: reducedMotion.matches ? 'auto' : 'smooth',
          block: 'nearest',
          inline: 'nearest',
        })
      } else {
        thumbnail.removeAttribute('aria-current')
      }
    }
  }

  /** Shows one of a stop's photos, moving the card's timer to where it is shown. */
  function selectPhoto(stop: TripDataStop, imageIndex: number) {
    const slide = slides[index]
    const delay = getPhotoDelay(slide)
    const duration = getDuration(slide) ?? 0
    const start = getPhotoStarts(stop, getTiming())[imageIndex] ?? 0

    elapsed = delay + (duration - delay) * start
    changePhoto(() => showPhoto(stop, imageIndex))
    renderProgress()
  }

  /**
   * Steps through the current stop's photos, for ↑ and ↓ and a swipe on the photo.
   * Past the last photo it moves on to the next card; before the first, back to the
   * card before, at its last photo.
   */
  function stepPhoto(direction: 1 | -1) {
    const slide = slides[index]
    const images = slide?.type === 'stop' ? slide.stop.images : []
    const next = photoIndex + direction

    if (slide?.type === 'stop' && next >= 0 && next < images.length) {
      selectPhoto(slide.stop, next)
      return
    }

    const from = index

    goTo(index + direction)

    const previous = slides[index]

    if (
      direction < 0 &&
      index !== from &&
      previous?.type === 'stop' &&
      previous.stop.images.length > 1
    ) {
      selectPhoto(previous.stop, previous.stop.images.length - 1)
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
    // Presenting or recording skips the openers, so a day starts at the stop after them.
    const startsDay = isStageLayout()
      ? (slide: Slide, slideIndex: number) =>
          slide.type !== 'day' && slides[slideIndex - 1]?.type === 'day'
      : (slide: Slide) => slide.type === 'day'
    const openers = slides.flatMap((slide, slideIndex) =>
      startsDay(slide, slideIndex) ? [slideIndex] : [],
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

  function createThumbnails(stop: TripDataStop) {
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

  /** How long drawing the route from `points[from]` to `points[to - 1]` takes. */
  function getDriveTime(from: number, to: number) {
    const start = points[from]
    const end = points[to - 1]
    const km = start && end ? getDistanceKm(start.coordinates, end.coordinates) : 0

    // Grows quickly over the first stretch and slowly after, as distance feels.
    return (
      (SHORT_LEG + (LEG_DURATION - SHORT_LEG) * Math.sqrt(Math.min(km / LONG_LEG_KM, 1))) / speed
    )
  }

  function renderSpeedButton() {
    speedFlow.update(speed)
    speedButton.setAttribute('aria-label', `播放速度 ${speed}×`)
  }

  function renderMusicButton() {
    for (const button of [musicButton, stageMusicButton]) {
      const icon = button.firstElementChild

      button.setAttribute('aria-pressed', String(musicOn))
      icon?.classList.toggle('i-lucide-volume-2', musicOn)
      icon?.classList.toggle('i-lucide-volume-x', !musicOn)
    }
  }

  function toggleMusic() {
    musicOn = !musicOn
    writeMusicPreference(musicOn)
    renderMusicButton()
    syncAmbientMusic()

    if (staging?.music) {
      setMusicAudible(staging.music, musicOn)
    }
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

    // Paused playback only pauses the music, so it picks up where it was. A hidden tab
    // holds the card too (see tick), so the music waits with it.
    if (!playing || document.hidden) {
      void ambient?.context.suspend()
      return
    }

    if (ambient) {
      void ambient.context.resume()
      return
    }

    const loading = { src }
    pendingAmbient = loading

    void loadMusic(src, { audible: true, record: false }).then((music) => {
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

    // The outgoing stop's photo card goes now, so the side card below can't put the
    // next stop's first photo into it (renderStage builds the new one).
    stagePhotoCard.clear()

    // A stop's day and time ride with the car and its places are labelled on the map,
    // so the card shows no heading.
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
        'mt-3 max-h-[min(20dvh,9rem)] shrink-0 overflow-y-auto @max-[48rem]:max-h-[min(12dvh,6.5rem)] overscroll-contain [scrollbar-width:none] break-words whitespace-pre-line text-tx-2 [&::-webkit-scrollbar]:hidden',
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
        // As tall as the card has room for beside the progress, thumbnails, text and
        // buttons (about 24rem with the card's offset); on a phone, low enough that
        // the card leaves the map about half the screen.
        photo.className =
          'relative block h-auto max-h-[calc(100dvh-24rem)] w-auto max-w-full @max-[48rem]:max-h-[26dvh]'
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
        // Presenting or recording, the stage's card plays it instead.
        ...(stop.video && stop.images.length === 0 && !isStageLayout()
          ? [
              createVideoPlayer(
                stop.video,
                'mt-1 w-full shrink-0 rounded-md border border-border bg-inline',
              ),
            ]
          : []),
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
        const number = createElement('dd', 'font-mono text-lg tabular-nums', '')

        number.append(createCountUp(value))
        item.append(createElement('dt', 'text-xs text-tx-3', label), number)
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
    stagePhotoToken += 1

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
    stagePhotoCard.clear()

    if (slide.type === 'intro') {
      stageCenter.replaceChildren(createIntroCard(trip, covers))
      return
    }

    if (slide.type === 'summary') {
      stageCenter.replaceChildren(createSummaryCard(trip, siteLine))
      return
    }

    // Presenting or recording skips a day's opener, so a stop is all that's left.
    if (slide.type !== 'stop') {
      return
    }

    const { stop } = slide

    stageCenter.replaceChildren()
    // The photo card pops up once the camera has arrived at the stop.
    const token = stagePhotoToken

    void waitForMotion(photoDelay / speed).then(() => {
      if (token === stagePhotoToken) {
        stagePhotoCard.show(stop, slide.stopIndex)
        stagePhotoCard.showPhoto(stop, photoIndex)
      }
    })
  }

  /**
   * Notes in the recording where each day starts (the stop after its skipped opener)
   * and where the summary does.
   */
  function addChapter(slideIndex: number) {
    const previous = slides[slideIndex - 1]
    const slide = previous?.type === 'day' ? previous : slides[slideIndex]

    if (chapters && (slide?.type === 'day' || slide?.type === 'summary')) {
      chapters.log.add(
        slide.type === 'day' ? `第 ${slide.day} 天 · ${slide.date}` : '旅程回顾',
        Math.floor((performance.now() - chapters.start) / 1000),
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
    let anchor = ''

    try {
      anchor = decodeURIComponent(location.hash.slice(1))
    } catch {
      // A malformed hash names no card; the trip starts from the beginning.
    }

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

  function goTo(requestedIndex: number, options: { play?: boolean } = {}) {
    if (!trip || requestedIndex < 0 || requestedIndex >= slides.length) {
      return
    }

    // Skipping ahead while the map is still travelling jumps straight to the stop.
    const stepForward = requestedIndex === index + 1 && !traveling && !reducedMotion.matches
    let nextIndex = requestedIndex

    // Presenting or recording, the header's rolling day stands in for a day's opener.
    if (isStageLayout()) {
      const direction = requestedIndex < index ? -1 : 1

      while (slides[nextIndex]?.type === 'day') {
        nextIndex += direction
      }
    }

    const slide = slides[nextIndex]!

    travelToken += 1
    traveling = false
    currentWaypointPoint = -1

    held = false

    if (options.play !== undefined) {
      playing = options.play
    }

    // The skipped openers' waypoints run straight on into the slide's own.
    const [firstWaypoint] = getWaypointsBefore(slides[requestedIndex]!)
    const [lastStart, lastCount] = getWaypointsBefore(slide)
    const waypointCount = lastStart + lastCount - firstWaypoint

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
    // Where a drive to this stop starts, while it is still to be drawn.
    let arrivingFrom: number | undefined

    if (slide.type === 'stop' && legStart !== -1 && !(legStart === undefined && arrived)) {
      arrivingFrom = legStart ?? Math.max(getStopPlaceStart(slide.stopIndex) - 1, 0)
    }

    // The photos wait for the drive here, if there is one. Presenting or recording,
    // the photo card pops up a moment later even when the route is already there.
    photoDelay =
      slide.type !== 'stop' || reducedMotion.matches
        ? 0
        : arrivingFrom !== undefined
          ? planLeg(arrivingFrom, getStopPlaceStart(slide.stopIndex) + 1).duration * speed
          : isStageLayout()
            ? CARD_POP
            : 0

    index = nextIndex
    elapsed = 0
    driveElapsed = 0
    syncHash(slide)
    addChapter(nextIndex)

    if (staging?.recording) {
      preparePhotosAhead()
    }

    // Autoplay stays quiet, like a carousel; stepping through by hand names each card.
    announcer.textContent = playing ? '' : getSlideLabel(slide)

    if (!getDuration(slide)) {
      playing = false
    }

    changeCard(renderSlide)

    routeProgress = undefined
    let arrival: Promise<void> | undefined

    if (slide.type === 'stop') {
      const arriveAt = getStopPlaceStart(slide.stopIndex) + 1

      // The previous card's route usually arrived already; only a skip ahead or
      // the last leg after waypoints still has to be drawn.
      if (arrivingFrom === undefined) {
        cancelLeg()
        drawnPointCount = arriveAt
        setRoute(drawnPointCount)
      } else {
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

    // A recording ends a moment after the summary; a presentation stays on it until
    // it is closed, its music fading out.
    if (staging && slide.type === 'summary') {
      if (staging.music) {
        fadeMusicOut(staging.music, EXPORT_SUMMARY_HOLD / 1000)
      }

      if (staging.recording) {
        staging.stopTimer = setTimeout(stopStage, EXPORT_SUMMARY_HOLD)
      }
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

    if (key === 'm') {
      event.preventDefault()
      toggleMusic()
      return
    }

    // A recording plays straight through: a stray key would end up in the video.
    if (activeStage?.mode === 'record') {
      return
    }

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

    // Both requests need the click that started this, so they come first.
    const stream = mode === 'record' ? await requestTabCapture() : undefined

    if (mode === 'record' && !stream) {
      return
    }

    if (mode === 'present') {
      // Full screen is a nicety; some browsers never settle the request, so don't wait.
      void document.documentElement.requestFullscreen?.().catch(() => {})
    }

    const token = {}
    stageToken = token
    activeStage = { mode, speed }
    wakeLock.hold()

    if (mode === 'record') {
      speed = 1
      renderSpeedButton()
    }

    stopAmbientMusic()
    // The heading is up from the start, so it opens on the trip's first moment.
    stageHeading.set(getClock(points, 0), { animate: false })
    page.dataset.exportRatio = ratio
    page.toggleAttribute('data-trip-presenting', mode === 'present')
    page.toggleAttribute(
      'data-trip-safe-area',
      safeArea && mode === 'record' && ratio === 'portrait',
    )
    wakeControls()
    getMap()?.resize()

    if (stream) {
      await cropTo(stream, page)
    }

    const music = await loadMusic(exportMusic.value, {
      audible: musicOn,
      record: mode === 'record',
    })

    // Stopped while the music loaded: let go of what was opened for it.
    if (stageToken !== token) {
      if (music) {
        closeMusic(music)
      }

      if (stream) {
        releaseCapture(stream)
      }

      return
    }

    const recording =
      stream &&
      createRecording(
        stream,
        page,
        music?.destination?.stream.getAudioTracks() ?? [],
        (video, extension) => {
          const name = `${currentTrip.title}-${ratio === 'portrait' ? '竖屏' : '横屏'}`
          const chapterText = chapters?.log.toString()

          finishStage()
          downloadBlob(video, `${name}.${extension}`)

          if (chapterText) {
            downloadBlob(new Blob([chapterText], { type: 'text/plain' }), `${name}-章节.txt`)
          }
        },
      )

    // Ending the capture from the browser's own bar ends the recording.
    stream?.getVideoTracks()[0]?.addEventListener('ended', stopStage)
    staging = {
      mode,
      ...(music ? { music } : {}),
      ...(recording ? { recording } : {}),
    }

    // Reset to a still intro first: the camera jumps to the whole route and the
    // tiles load before the first frame, so playback doesn't open mid-flight.
    goTo(0, { play: false })
    frameTrip({ instant: true })
    await waitForMapIdle()
    await recording?.ready

    if (stageToken !== token) {
      return
    }

    if (recording) {
      recording.start()
      chapters = { log: createChapterLog(currentTrip.title), start: performance.now() }
    }

    if (music) {
      fadeMusicIn(music)
    }

    elapsed = 0
    driveElapsed = 0
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
    if (staging?.recording?.active) {
      staging.recording.stop()
    } else {
      finishStage()
    }
  }

  function finishStage() {
    // Still starting (loading its music), the stage has nothing of its own to release
    // yet, but its layout is already up.
    const starting = stageToken !== undefined && !staging

    stageToken = undefined
    chapters = undefined
    wakeLock.release()

    if (!staging && !starting) {
      return
    }

    const presenting = page.hasAttribute('data-trip-presenting')

    if (staging) {
      clearTimeout(staging.stopTimer)

      staging.recording?.release()

      if (staging.music) {
        closeMusic(staging.music)
      }
    }

    staging = undefined

    if (activeStage) {
      speed = activeStage.speed
      activeStage = undefined
      renderSpeedButton()
    }

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

    if (!staging?.recording || !first || readyPhotos.has(first.src)) {
      return true
    }

    preparePhotosAhead()
    return false
  }

  function tick(now: number) {
    frame = requestAnimationFrame(tick)

    const delta = lastFrameTime === undefined ? 0 : now - lastFrameTime
    const duration = getDuration(slides[index])

    lastFrameTime = now

    if (held) {
      camera.hold(now)
    } else {
      motionTime += delta
      camera.update(now)
    }

    // The card's timer waits while the map travels between waypoints or the mouse rests on it.
    if (!playing || !duration || document.hidden || traveling || (hovering && !staging)) {
      return
    }

    elapsed += delta * speed
    driveElapsed += delta * speed
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
      request = fetch(getTripDataUrl(id), { signal })
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

  /** Loads a trip's data ahead of time, when it looks likely to be opened next. */
  function prefetch(id: string) {
    if (tripTitles.has(id) && !loadedTrips.has(id)) {
      void loadTrip(id)
    }
  }

  function begin(nextTrip: TripData) {
    // A trip page renders its intro card already; only a card opened here slides in.
    const rendered = !trip && !panel.hidden
    stop()
    trip = nextTrip
    covers = getIntroCovers(nextTrip)
    exportMusic.value = nextTrip.music ?? exportMusic.options[0]?.value ?? ''
    // A waypoint has its own day and time; a stop's places take the stop's.
    const toPoint = (
      place: TripPlace,
      when: Pick<TripDataWaypoint, 'date' | 'day' | 'time'>,
      stopIndex: number,
      waypoint: boolean,
    ): TripPoint => ({
      coordinates: [place.longitude, place.latitude],
      date: when.date,
      day: when.day,
      name: place.name,
      stopIndex,
      time: when.time,
      waypoint,
    })

    points = [
      ...nextTrip.stops.flatMap((stop, stopIndex) => [
        ...stop.waypoints.map((waypoint) => toPoint(waypoint, waypoint, stopIndex, true)),
        ...stop.places.map((place) => toPoint(place, stop, stopIndex, false)),
      ]),
      ...nextTrip.trailingWaypoints.map((waypoint) =>
        toPoint(waypoint, waypoint, nextTrip.stops.length, true),
      ),
    ]
    stopPointEnds = getStopPointEnds(nextTrip.stops)
    slides = buildSlides(nextTrip.stops)
    progress.replaceChildren(
      ...slides.map((slide, slideIndex) => {
        const item = createElement('li', 'flex-1', '')
        item.dataset.slide = slide.type
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
        // A pointer is handled by the bar as a whole (see the scrubbing below); this
        // is Enter or Space on a focused segment.
        button.addEventListener(
          'click',
          (event) => {
            if (event.detail === 0) {
              goTo(slideIndex)
            }
          },
          { signal },
        )
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

    if (!rendered && !reducedMotion.matches && !isStageLayout()) {
      panel.animate(PANEL_HIDDEN.toReversed(), PANEL_MOTION)
    }
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

    leavePanel()
    travelToken += 1
    traveling = false
    cancelLeg()
    mapLabels.destroy()
    layers.remove()
    camera.stop()
    getMap()?.keyboard.enable()
    routeTip = undefined
    trip = undefined
    slides = []
    playing = false
    held = false
    panel.hidden = true
    progress.replaceChildren()
    hideProgressTip()
    body.replaceChildren()
  }

  /**
   * Leaves a copy of the card fading out where it was, since the card itself is
   * emptied and hidden at once. Its media are swapped for empty boxes the same size,
   * so the copy plays and loads nothing.
   */
  function leavePanel() {
    if (panel.hidden || reducedMotion.matches || isStageLayout()) {
      return
    }

    cardTransition.end()
    photoTransition.end()

    const ghost = panel.cloneNode(true) as HTMLElement

    for (const media of ghost.querySelectorAll('video, iframe, audio')) {
      const box = document.createElement('div')
      box.className = media.className
      box.style.cssText = (media as HTMLElement).style.cssText
      media.replaceWith(box)
    }

    for (const element of ghost.querySelectorAll('[id]')) {
      element.removeAttribute('id')
    }

    ghost.removeAttribute('aria-labelledby')
    ghost.setAttribute('aria-hidden', 'true')
    ghost.inert = true
    ghost.style.pointerEvents = 'none'
    panel.after(ghost)
    ghost.animate(PANEL_HIDDEN, PANEL_MOTION).finished.then(
      () => ghost.remove(),
      () => ghost.remove(),
    )
    signal.addEventListener('abort', () => ghost.remove(), { once: true })
  }

  /** The progress segment under `x`, clamped to the bar's ends. */
  function getProgressIndexAt(x: number) {
    const rect = progress.getBoundingClientRect()
    const fraction = Math.min(Math.max((x - rect.left) / Math.max(rect.width, 1), 0), 0.9999)

    return Math.floor(fraction * progress.children.length)
  }

  // The segments are too narrow to hit one by one on a phone, so a finger can slide
  // along the bar, naming the card under it, and lands on that card when lifted.
  let scrubIndex: number | undefined

  progress.addEventListener(
    'pointerdown',
    (event) => {
      if (event.button !== 0 || progress.children.length === 0) {
        return
      }

      scrubIndex = getProgressIndexAt(event.clientX)
      progress.setPointerCapture(event.pointerId)
    },
    { signal },
  )
  progress.addEventListener(
    'pointermove',
    (event) => {
      if (scrubIndex === undefined) {
        return
      }

      scrubIndex = getProgressIndexAt(event.clientX)
      const button = progress.children[scrubIndex]?.querySelector('button')

      if (button) {
        showProgressTip(button)
      }
    },
    { signal },
  )
  progress.addEventListener(
    'pointerup',
    (event) => {
      const target = scrubIndex

      scrubIndex = undefined

      if (event.pointerType !== 'mouse') {
        hideProgressTip()
      }

      if (target !== undefined) {
        goTo(target)
      }
    },
    { signal },
  )
  progress.addEventListener(
    'pointercancel',
    () => {
      scrubIndex = undefined
      hideProgressTip()
    },
    { signal },
  )
  // Sliding along the bar mustn't scroll or pull the page.
  progress.style.touchAction = 'none'
  previousButton.addEventListener('click', () => goTo(index - 1), { signal })
  nextButton.addEventListener('click', () => goTo(index + 1), { signal })
  closeButton.addEventListener('click', onClose, { signal })
  panel.addEventListener(
    'pointerenter',
    (event) => {
      hovering = event.pointerType === 'mouse'
      // The current progress segment breathes while the card is held (see index.css).
      panel.toggleAttribute('data-held', hovering)
    },
    { signal },
  )
  panel.addEventListener(
    'pointerleave',
    () => {
      hovering = false
      panel.removeAttribute('data-held')
    },
    { signal },
  )
  // The map's logo and attribution keep clear of the card (see index.css).
  const panelSize = new ResizeObserver(() => {
    page.style.setProperty('--trip-panel-height', `${panel.offsetHeight}px`)
  })
  panelSize.observe(panel)
  signal.addEventListener('abort', () => panelSize.disconnect())
  exportButton.hidden = !canRecord()
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
  musicButton.addEventListener('click', toggleMusic, { signal })
  stageMusicButton.addEventListener('click', toggleMusic, { signal })
  exportMusic.addEventListener('change', syncAmbientMusic, { signal })
  document.addEventListener('visibilitychange', syncAmbientMusic, { signal })
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
      if (!playing && !getDuration(slides[index])) {
        goTo(0, { play: true })
        return
      }

      playing = !playing
      held = !playing
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
  window.addEventListener(
    'resize',
    () => {
      const slide = slides[index]
      const image = slide?.type === 'stop' ? slide.stop.images[photoIndex] : undefined

      // The photo's size follows the room the card has.
      if (photo && image && !photoTransition.running) {
        sizePhoto(photo, image)
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
