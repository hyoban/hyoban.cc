import type { Map as MapboxMap } from 'mapbox-gl'
import { collectPanelElements, collectStageElements } from '../elements.ts'
import { getDistanceKm } from '../geo.ts'
import {
  renderShortcuts,
  renderTripPanel,
  renderTripStage,
  type Brand,
  type MusicTrack,
} from '../markup.ts'
import { t, type Messages } from '../messages.ts'
import { readTheme } from '../dom-theme.ts'
import type { Theme } from '../theme.ts'
import { cancelFrame, requestFrame } from '../frames.ts'
import { createAmbientMusic } from './ambient-music.ts'
import { createCamera } from './camera.ts'
import { createCarModel, type Flyover } from './car-model.ts'
import { createDayHeading } from './day-heading.ts'
import { createElement, perFrame, preloadImage, preloadThumbnail, setButtonIcon } from './dom.ts'
import { getIntroCovers } from './intro.ts'
import { getShortPlaceName } from './labels.ts'
import { createMapLabels } from './map-labels.ts'
import { getReducedMotionQuery } from './motion.ts'
import { setMusicAudible } from './music.ts'
import {
  getNextSpeed,
  readFlyoverPreference,
  readMusicPreference,
  readSpeedPreference,
  SPEEDS,
  writeFlyoverPreference,
  writeMusicPreference,
  writeSpeedPreference,
} from './preferences.ts'
import { createRouteDrawer } from './route-drawer.ts'
import { createRoutePlan, type RoutePlan } from './route-plan.ts'
import { createSideCard } from './side-card.ts'
import { getSlideDuration, getSlideLabel, type SlideTiming } from './slides.ts'
import { createStage } from './stage.ts'
import {
  findAnchorSlide,
  findDayTarget,
  getPhotoTime,
  getSlideAnchor,
  resolveSlideIndex,
  stepThroughPhotos,
} from './timeline.ts'
import { createTripLoader } from './trip-loader.ts'
import type {
  MapLabel,
  Slide,
  TripData,
  TripDataStop,
  TripImage,
  TripPoint,
  TripSummary,
} from './types.ts'

// Plays a trip back on the map: the route draws itself from place to place while a
// card shows each stop's photos and words. The same playback can run in a story
// layout to present full screen or to record as a video. This keeps the time and
// decides what happens when; the route drawer, the side card, the stage and the
// camera each do their part.
//
// One player per page: its shortcuts listen on the window.

export type TripPlayerOptions = {
  /** Who made the video, on the presentation stage's corner. */
  brand?: Brand | undefined
  /** Fetches a trip's `TripData`; undefined when it can't be had. */
  fetchTrip: (id: string) => Promise<TripData | undefined>
  getMap: () => MapboxMap | undefined
  /** The colours the route is drawn in on the map; the page's by default. */
  getMapTheme?: (() => Theme) | undefined
  /** The card the address names, a stop's anchor; without it, the address's hash. */
  getStop?: (() => string | undefined) | undefined
  /** A car to drive the route while flying over: see `TripMapOptions.carModel`. */
  carModel?: string | undefined
  /** The trip whose data the page already carries, to play without a fetch. */
  inlineTrip?: TripData | undefined
  messages: Messages
  /** The tracks a video may be set to. */
  music?: readonly MusicTrack[] | undefined
  onClose: () => void
  /** Puts the card playing in the address (a stop's anchor, or none); without it, in the hash. */
  setStop?: ((tripId: string, stop: string) => void) | undefined
  page: HTMLElement
  /** The side card's container; filled by `renderTripPanel` unless the page did. */
  panel: HTMLElement
  /** The shortcut panel's `<dialog>`; filled by `renderShortcuts` unless the page did. */
  shortcuts: HTMLDialogElement
  signal: AbortSignal
  /** Shown on the summary card of a video: where to find the trips, like `hyoban.cc/trip/`. */
  siteLine: string
  /** The presentation stage's container; filled by `renderTripStage` unless the page did. */
  stage: HTMLElement
  trips: readonly TripSummary[]
}

// Long enough to read a waypoint's name before driving on.
const WAYPOINT_PAUSE = 1000
export const STOP_ZOOM = 9
// The closest the camera comes in while following a short drive.
export const NEARBY_MAX_ZOOM = 13
// Presenting or recording, a stop's photo card pops up this soon when the route is
// already there; otherwise once the drive there is drawn.
const CARD_POP = 400
// The address bar follows the playing card so it can be shared, but reloading the page
// should start the trip over rather than resume mid-way. Only the first trip started
// after a reload ignores the card; links followed later in the same page still work.
let reloadPending: boolean | undefined

function takeReload() {
  reloadPending ??=
    (performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined)
      ?.type === 'reload'

  const reloaded = reloadPending
  reloadPending = false
  return reloaded
}

export function createTripPlayer(options: TripPlayerOptions) {
  const { getMap, messages, onClose, page, signal, siteLine } = options
  const reducedMotion = getReducedMotionQuery()

  // The containers come empty unless the page rendered them on the server.
  if (!options.panel.querySelector('[data-trip-map-progress]')) {
    options.panel.innerHTML = renderTripPanel({
      messages,
      music: options.music,
      trip: options.inlineTrip,
    })
  }

  if (!options.stage.querySelector('[data-trip-map-stage-center]')) {
    options.stage.innerHTML = renderTripStage({ brand: options.brand, messages })
  }

  if (!options.shortcuts.querySelector('[id="trip-map-shortcuts-title"]')) {
    options.shortcuts.innerHTML = renderShortcuts(messages)
  }

  const panel = collectPanelElements(options.panel)
  const stageElements = collectStageElements(options.stage)
  const shortcutsDialog = options.shortcuts
  const loader = createTripLoader({
    fetchTrip: options.fetchTrip,
    inline: options.inlineTrip,
    trips: options.trips,
  })
  // The trip waiting on its data; `activeId` reports it so the map stays in trip mode.
  let requestedId: string | undefined
  let trip: TripData | undefined
  let plan: RoutePlan | undefined
  // Photos for the intro and summary.
  let covers: TripImage[] = []
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
  let musicOn = readMusicPreference()
  let flyover = readFlyoverPreference()
  // Flying over was turned on or off while paused: the camera moves to it anyway.
  let reframing = false
  let frame: number | undefined
  let lastFrameTime: number | undefined
  // Bumped on every slide change so an unfinished trip between waypoints stops.
  let travelToken = 0
  let traveling = false
  let currentWaypointPoint = -1
  // How long the current stop waits for the route to arrive before its photos' time
  // starts, in the card's own time.
  let photoDelay = 0
  // The speed's digits roll from one to the next.
  const speedFlow = document.createElement('number-flow')

  speedFlow.numberSuffix = '×'
  panel.speed.replaceChildren(speedFlow)

  const points = (): readonly TripPoint[] => plan?.points ?? []

  // The camera and the labels each read the layout every frame. Whichever runs first in
  // a frame measures, before anything is written, and the other reuses it; reading it
  // again after the writes would make the browser lay out a second time.
  const getFrameLayout = perFrame(readLayout)
  const mapLabels = createMapLabels({
    getLayout: getFrameLayout,
    getMap,
    // Every stop's dot, and the car, ship or plane while the route is drawn.
    getMarkers: () => [
      ...points().map((point) => ({
        coordinates: point.coordinates,
        radius: point.waypoint ? 7 : 10,
      })),
      ...(drawer.routeTip ? [{ coordinates: drawer.routeTip, radius: 16 }] : []),
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
        !stage.isStageLayout() && headClock.getClientRects().length > 0
          ? [headClock.getBoundingClientRect()]
          : [],
      blocked: readBlocked(),
      container: getMap()?.getContainer().getBoundingClientRect(),
      padding: getCameraPadding(),
    }
  }

  function readBlocked() {
    if (stage.isStageLayout()) {
      return stage.getBlocked()
    }

    const shown = (element: Element | null | undefined) =>
      element &&
      element.getClientRects().length > 0 &&
      getComputedStyle(element).visibility !== 'hidden'
        ? [element.getBoundingClientRect()]
        : []

    return [...shown(panel.panel), ...shown(page.querySelector('[data-trip-map-toolbar]'))]
  }

  // The page itself becomes a narrow stage while recording or presenting, so measure it.
  function isNarrow() {
    return page.clientWidth <= 768
  }

  function getCameraPadding() {
    if (stage.isStageLayout()) {
      return stage.getPadding()
    }

    if (isNarrow()) {
      // Leaves room for the one-row toolbar, which returns when playback pauses.
      return { bottom: panel.panel.offsetHeight + 40, left: 40, right: 40, top: 72 }
    }

    // The toolbar is hidden during playback, so the top needs little room.
    return { bottom: 64, left: panel.panel.offsetWidth + 64, right: 64, top: 64 }
  }

  const camera = createCamera({
    getFlyover: () => flyover,
    // The tip while the route is drawn, else the last place it reached.
    getFocus: () =>
      drawer.routeTip ?? points()[Math.max(drawer.drawnPointCount - 1, 0)]?.coordinates,
    getMap,
    getPadding: () => getFrameLayout().padding,
    getSpeed: () => speed,
    reducedMotion,
  })
  // On the map, the day and time ride beside the car, ship or plane.
  const headClock = createElement('div', 'trip-map-head__clock', '')
  const headClockDate = createElement('p', 'trip-map-head__date', '')
  const headClockTime = createElement('p', 'trip-map-head__time', '')
  const clockHeading = createDayHeading(headClockDate, headClockTime, messages)
  // Photos already asked for, so going back and forth doesn't ask again.
  const preloaded = new Set<string>()

  headClock.append(headClockDate, headClockTime)

  const flyoverMode: Flyover = {
    car: options.carModel ? createCarModel(options.carModel) : undefined,
    isOn: () => flyover,
  }
  const drawer = createRouteDrawer({
    flyover: flyoverMode,
    getDistanceKm,
    getMap,
    getMotionTime: () => motionTime,
    getSpeed: () => speed,
    getStatus: () => ({
      currentStopIndex: currentStopIndex(),
      currentWaypointPoint,
      traveling,
    }),
    getTheme: options.getMapTheme ?? (() => readTheme(page)),
    headClock,
    onClock: (clock, clockOptions) =>
      (stage.isStageLayout() ? stage.heading : clockHeading).set(clock, clockOptions),
    reducedMotion,
  })
  const stage = createStage({
    elements: stageElements,
    frameTrip,
    getMap,
    getPoints: points,
    getSlides: () => slides,
    getSpeed: () => speed,
    getTrip: () => trip,
    goTo,
    isMusicOn: () => musicOn,
    messages,
    onFinish: renderSlide,
    onStart: () => {
      elapsed = 0
      driveElapsed = 0
      playing = true
      renderPlayButton()
    },
    page,
    panel,
    setSpeed,
    signal,
    siteLine,
    waitForMotion,
  })
  const card = createSideCard({
    elements: panel,
    isStageLayout: stage.isStageLayout,
    messages,
    onJump: (slideIndex) => goTo(slideIndex),
    onPhotoShown: (stop, imageIndex) => stage.photoCard.showPhoto(stop, imageIndex),
    onReplay: () => goTo(0, { play: true }),
    onSelectPhoto: selectPhoto,
    onStepPhoto: stepPhoto,
    reducedMotion,
    signal,
  })
  const ambient = createAmbientMusic({
    getSource: () => panel.setupMusic.value || panel.setupMusic.options[0]?.value || '',
    isPlaying: () => playing && !document.hidden,
    isWanted: () => musicOn && trip !== undefined && !stage.staging,
  })

  function currentStopIndex() {
    const slide = slides[index]
    return slide?.type === 'stop' ? slide.stopIndex : -1
  }

  /**
   * A stop's photos are timed from when the route reaches it (presenting or recording,
   * that's also when its photo card appears), so the stop lasts that much longer and
   * the first and last photos get as long as the others.
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

  function frameTrip(frameOptions: { instant?: boolean } = {}) {
    camera.overview(
      points().map((point) => point.coordinates),
      STOP_ZOOM,
      frameOptions,
    )
  }

  /**
   * Stays where the day sets off (where the night was spent, or the last waypoint
   * passed the evening before) and eases to a zoom for the day's first drive.
   */
  function frameDay(slide: Extract<Slide, { type: 'day' }>) {
    const stop = trip?.stops[slide.stopIndex]

    if (!stop || !plan) {
      return
    }

    const [first, count] = plan.waypointsBefore(slide)
    const start = plan.pointStart(slide.stopIndex)
    // A travel day drives through its own waypoints; any other day on to its first stop.
    const end = slide.waypointsOnly
      ? start + stop.waypoints.findLastIndex((waypoint) => waypoint.day === slide.day)
      : plan.placeStart(slide.stopIndex)

    camera.follow(plan.line(Math.max(first + count - 1, 0), end + 1), NEARBY_MAX_ZOOM - 1)
  }

  /**
   * Follows the drive on a stop's card: the drive here while it is still being
   * drawn from `arrivingFrom`, else the drive on to the next place.
   */
  function frameStop(stopIndex: number, arrivingFrom?: number) {
    if (!plan) {
      return
    }

    const placeStart = plan.placeStart(stopIndex)
    const stretch =
      arrivingFrom === undefined
        ? plan.line(placeStart, plan.onwardEnd(stopIndex) + 1)
        : plan.line(arrivingFrom, placeStart + 1)
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
    mapLabels.show(labels, { alignLeft: !isNarrow() && !stage.isStageLayout() })
  }

  /**
   * Flies through `points[first..first + count)` one by one, drawing the route and
   * pausing at each, then calls `arrive`. Gives up when another slide takes over.
   */
  async function travelThroughWaypoints(first: number, count: number, arrive: () => void) {
    const token = travelToken

    traveling = true

    if (stage.isStageLayout()) {
      stage.photoCard.clear()
    }

    for (let pointIndex = first; pointIndex < first + count; pointIndex += 1) {
      const point = points()[pointIndex]!
      const legStart = Math.max(pointIndex - 1, 0)

      currentWaypointPoint = pointIndex
      drawer.updateStops()
      showLabels([{ coordinates: point.coordinates, name: getShortPlaceName(point.name) }])
      camera.follow(plan!.line(legStart, pointIndex + 1), STOP_ZOOM)
      await Promise.all([
        drawer.drawnPointCount >= pointIndex + 1
          ? Promise.resolve()
          : drawer.drawLeg(legStart, pointIndex + 1),
        waitForMotion(
          reducedMotion.matches ? 0 : drawer.planLeg(legStart, pointIndex + 1).duration,
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
    while (card.hovering && !stage.staging) {
      await new Promise((resolve) => setTimeout(resolve, 100))

      if (token !== travelToken) {
        return
      }
    }

    traveling = false
    currentWaypointPoint = -1
    drawer.updateStops()
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

  function renderProgress() {
    const slide = slides[index]
    const duration = getDuration(slide)

    card.renderProgress(index, elapsed, duration)

    if (slide?.type === 'stop' && duration && slide.stop.images.length > 1) {
      // The photos share the time from when the card shows them, by their slots.
      const delay = getPhotoDelay(slide)
      const shown = Math.max(elapsed - delay, 0) / Math.max(duration - delay, 1)

      card.syncPhoto(slide.stop, shown, getTiming())
    }

    drawer.renderProgress(slide, duration, driveElapsed)
  }

  /** Shows one of a stop's photos, moving the card's timer to where it is shown. */
  function selectPhoto(stop: TripDataStop, imageIndex: number) {
    const slide = slides[index]

    elapsed = getPhotoTime(
      stop,
      imageIndex,
      getDuration(slide) ?? 0,
      getPhotoDelay(slide),
      getTiming(),
    )
    card.changePhoto(() => card.showPhoto(stop, imageIndex))
    renderProgress()
  }

  /**
   * Steps through the current stop's photos, for ↑ and ↓ and a swipe on the photo.
   * Past the last photo it moves on to the next card; before the first, back to the
   * card before, at its last photo.
   */
  function stepPhoto(direction: 1 | -1) {
    const slide = slides[index]
    const step = stepThroughPhotos(
      slide?.type === 'stop' ? slide.stop.images.length : 0,
      card.photoIndex,
      direction,
    )

    if (step.type === 'photo' && slide?.type === 'stop') {
      selectPhoto(slide.stop, step.photo)
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

  /** Jumps to the next day's opener, or back to this day's (then the one before). */
  function stepDay(direction: number) {
    goTo(findDayTarget(slides, index, direction, stage.isStageLayout()))
  }

  function setSpeed(next: number) {
    speed = next
    writeSpeedPreference(speed)
    renderSpeedButton()
  }

  function renderPlayButton() {
    const action = playing ? messages.pause : messages.play

    panel.play.setAttribute('aria-label', action)
    panel.play.title = t(messages, 'withKey', { action, key: messages.keySpace })
    setButtonIcon(panel.play, playing ? 'pause' : 'play')
    // The toolbar steps aside while playing and returns when paused or finished.
    page.toggleAttribute('data-trip-map-playing', playing)
    ambient.sync()
  }

  function renderSpeedButton() {
    speedFlow.update(speed)
    panel.speed.setAttribute('aria-label', t(messages, 'playbackSpeed', { speed }))
  }

  function renderMusicButton() {
    for (const button of [panel.music, stageElements.music]) {
      button.setAttribute('aria-pressed', String(musicOn))
      setButtonIcon(button, musicOn ? 'volume-2' : 'volume-x')
    }
  }

  function renderFlyoverButton() {
    panel.flyover.setAttribute('aria-pressed', String(flyover))
  }

  function toggleFlyover() {
    flyover = !flyover
    reframing = true
    writeFlyoverPreference(flyover)
    renderFlyoverButton()
  }

  function toggleMusic() {
    musicOn = !musicOn
    writeMusicPreference(musicOn)
    renderMusicButton()
    ambient.sync()

    const music = stage.staging?.music

    if (music) {
      setMusicAudible(music, musicOn)
    }
  }

  function renderSlide() {
    const slide = slides[index]

    if (!trip || !slide) {
      return
    }

    // The outgoing stop's photo card goes now, so the side card below can't put the
    // next stop's first photo into it (the stage builds the new one).
    stage.photoCard.clear()
    card.render({ covers, index, slide, slideCount: slides.length, trip })
    preloadNextStop()
    stage.render(slide, { covers, photoDelay, photoIndex: card.photoIndex })
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

  /** The slide a shared link names, or the first; a reload starts the trip over. */
  function getHashSlideIndex() {
    const reloaded = takeReload()
    let anchor = ''

    try {
      anchor = options.getStop
        ? (options.getStop() ?? '')
        : decodeURIComponent(location.hash.slice(1))
    } catch {
      // A malformed hash names no card; the trip starts from the beginning.
    }

    return reloaded || !trip ? 0 : findAnchorSlide(slides, trip.stops, anchor)
  }

  /** Keeps the address bar pointing at the current card, so it can be shared. */
  function syncHash(slide: Slide) {
    if (options.setStop) {
      if (trip) {
        options.setStop(trip.id, getSlideAnchor(slide, trip.stops))
      }

      return
    }

    const url = new URL(location.href)

    url.hash = trip ? getSlideAnchor(slide, trip.stops) : ''

    if (url.href !== location.href) {
      history.replaceState(history.state, '', url)
    }
  }

  function goTo(requestedIndex: number, goOptions: { play?: boolean } = {}) {
    if (!trip || !plan || requestedIndex < 0 || requestedIndex >= slides.length) {
      return
    }

    // Skipping ahead while the map is still travelling jumps straight to the stop.
    const stepForward = requestedIndex === index + 1 && !traveling && !reducedMotion.matches
    const nextIndex = resolveSlideIndex(slides, index, requestedIndex, stage.isStageLayout())
    const slide = slides[nextIndex]!

    travelToken += 1
    traveling = false
    currentWaypointPoint = -1
    held = false

    if (goOptions.play !== undefined) {
      playing = goOptions.play
    }

    // The skipped openers' waypoints run straight on into the slide's own.
    const [firstWaypoint] = plan.waypointsBefore(slides[requestedIndex]!)
    const [lastStart, lastCount] = plan.waypointsBefore(slide)
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
    const route = plan!

    // Taken before rendering, which already starts the route onward from this stop.
    const arrived =
      slide.type === 'stop' && drawer.drawnPointCount >= route.placeStart(slide.stopIndex) + 1
    // Where a drive to this stop starts, while it is still to be drawn.
    let arrivingFrom: number | undefined

    if (slide.type === 'stop' && legStart !== -1 && !(legStart === undefined && arrived)) {
      arrivingFrom = legStart ?? Math.max(route.placeStart(slide.stopIndex) - 1, 0)
    }

    // The photos wait for the drive here, if there is one. Presenting or recording,
    // the photo card pops up a moment later even when the route is already there.
    photoDelay =
      slide.type !== 'stop' || reducedMotion.matches
        ? 0
        : arrivingFrom !== undefined
          ? drawer.planLeg(arrivingFrom, route.placeStart(slide.stopIndex) + 1).duration * speed
          : stage.isStageLayout()
            ? CARD_POP
            : 0

    index = nextIndex
    elapsed = 0
    driveElapsed = 0
    syncHash(slide)
    stage.addChapter(nextIndex)

    if (stage.staging?.recording) {
      stage.preparePhotosAhead(index)
    }

    // Autoplay stays quiet, like a carousel; stepping through by hand names each card.
    card.announce(playing ? '' : getSlideLabel(slide, messages))

    if (!getDuration(slide)) {
      playing = false
    }

    card.change(renderSlide)
    drawer.resetProgress()

    let arrival: Promise<void> | undefined

    if (slide.type === 'stop') {
      const arriveAt = route.placeStart(slide.stopIndex) + 1

      // The previous card's route usually arrived already; only a skip ahead or
      // the last leg after waypoints still has to be drawn.
      if (arrivingFrom === undefined) {
        drawer.cancelLeg()
        drawer.drawnPointCount = arriveAt
        drawer.setRoute(arriveAt)
      } else {
        arrival = drawer.drawLeg(arrivingFrom, arriveAt)
      }
    } else if (slide.type === 'day') {
      // Usually the previous stop's route already reached here; jumps set it directly.
      if (legStart === -1 || drawer.drawnPointCount === 0) {
        drawer.cancelLeg()
        // Drawn through the waypoints passed before this card, or to the first point.
        const [first, count] = route.waypointsBefore(slide)
        drawer.drawnPointCount = Math.min(Math.max(first + count, 1), route.points.length)
        drawer.setRoute(drawer.drawnPointCount)
      }
    } else {
      drawer.cancelLeg()
      drawer.drawnPointCount = slide.type === 'intro' ? 0 : route.points.length
      drawer.setRoute(drawer.drawnPointCount)
    }

    drawer.updateStops()
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

    renderProgress()

    if (slide.type === 'summary') {
      stage.onSummary()
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
    if (stage.setupOpen && event.key === 'Escape') {
      event.preventDefault()
      stage.closeSetup()
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
    if (stage.pending && event.key === 'Escape') {
      event.preventDefault()
      stage.stop()
      return
    }

    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key

    if (key === 'm') {
      event.preventDefault()
      toggleMusic()
      return
    }

    // A recording plays straight through: a stray key would end up in the video.
    if (stage.active?.mode === 'record') {
      return
    }

    const faster = () => setSpeed(SPEEDS.find((value) => value > speed) ?? speed)
    const actions: Record<string, () => void> = {
      ' ': () => panel.play.click(),
      '-': () => setSpeed(SPEEDS.findLast((value) => value < speed) ?? speed),
      '=': faster,
      '+': faster,
      '?': () => shortcutsDialog.showModal(),
      ArrowDown: () => stepPhoto(1),
      ArrowLeft: () => (event.shiftKey ? stepDay(-1) : goTo(index - 1)),
      ArrowRight: () => (event.shiftKey ? stepDay(1) : goTo(index + 1)),
      ArrowUp: () => stepPhoto(-1),
      End: () => goTo(slides.length - 1),
      Home: () => goTo(0),
      v: toggleFlyover,
      f: () => {
        if (!stage.staging) {
          void stage.start(innerWidth >= innerHeight ? 'landscape' : 'portrait', 'present')
        }
      },
      k: () => panel.play.click(),
      r: () => {
        if (!stage.staging && !panel.exportButton.hidden) {
          stage.openSetup('record')
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

  function tick(now: number) {
    frame = requestFrame(tick)

    const delta = lastFrameTime === undefined ? 0 : now - lastFrameTime
    const duration = getDuration(slides[index])

    lastFrameTime = now

    if (held && reframing) {
      reframing = camera.update(now)
    } else if (held) {
      camera.hold(now)
    } else {
      reframing = false
      motionTime += delta
      camera.update(now)
    }

    // The card's timer waits while the map travels between waypoints or the mouse rests on it.
    if (
      !playing ||
      !duration ||
      document.hidden ||
      traveling ||
      (card.hovering && !stage.staging)
    ) {
      return
    }

    elapsed += delta * speed
    driveElapsed += delta * speed
    renderProgress()

    // A slow photo holds the current card a little longer rather than record a blank.
    if (elapsed >= duration && stage.isNextSlideReady(index)) {
      goTo(index + 1)
    }
  }

  function start(id: string) {
    if (!loader.has(id) || trip?.id === id || requestedId === id) {
      return
    }

    const loadedTrip = loader.get(id)

    if (loadedTrip) {
      begin(loadedTrip)
      return
    }

    stop()
    requestedId = id
    void loader.load(id).then((data) => {
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

  function begin(nextTrip: TripData) {
    // A trip page renders its intro card already; only a card opened here slides in.
    const rendered = !trip && !panel.panel.hidden

    stop()
    trip = nextTrip
    plan = createRoutePlan(nextTrip)
    slides = plan.slides
    covers = getIntroCovers(nextTrip)
    panel.setupMusic.value = nextTrip.music ?? panel.setupMusic.options[0]?.value ?? ''
    drawer.load(plan)
    card.buildProgress(slides)
    card.enter(rendered)
    drawer.addLayers()
    index = -1
    goTo(getHashSlideIndex(), { play: !reducedMotion.matches })
    renderPlayButton()
    lastFrameTime = undefined
    frame = requestFrame(tick)
    // What started it, a trip's link or its card, has gone from the page by the next
    // frame: the focus goes on to the controls rather than back to the top of the page.
    requestAnimationFrame(() => {
      if (
        trip === nextTrip &&
        (!document.activeElement || document.activeElement === document.body)
      ) {
        panel.play.focus({ preventScroll: true })
      }
    })
  }

  function stop() {
    requestedId = undefined

    if (!trip) {
      return
    }

    stage.stop()
    ambient.stop()
    stage.closeSetup()
    page.removeAttribute('data-trip-map-playing')

    if (frame !== undefined) {
      cancelFrame(frame)
      frame = undefined
    }

    card.leave()
    travelToken += 1
    traveling = false
    drawer.remove()
    mapLabels.destroy()
    camera.stop()
    trip = undefined
    plan = undefined
    slides = []
    playing = false
    held = false
    card.clear()
  }

  panel.previous.addEventListener('click', () => goTo(index - 1), { signal })
  panel.next.addEventListener('click', () => goTo(index + 1), { signal })
  panel.close.addEventListener('click', onClose, { signal })
  // The map's logo and attribution keep clear of the card (see styles.css).
  const panelSize = new ResizeObserver(() => {
    page.style.setProperty('--trip-panel-height', `${panel.panel.offsetHeight}px`)
  })
  panelSize.observe(panel.panel)
  signal.addEventListener('abort', () => panelSize.disconnect())
  renderSpeedButton()
  panel.speed.addEventListener('click', () => setSpeed(getNextSpeed(speed)), { signal })
  renderFlyoverButton()
  panel.flyover.addEventListener('click', toggleFlyover, { signal })
  renderMusicButton()
  panel.music.addEventListener('click', toggleMusic, { signal })
  stageElements.music.addEventListener('click', toggleMusic, { signal })
  panel.setupMusic.addEventListener('change', ambient.sync, { signal })
  document.addEventListener('visibilitychange', ambient.sync, { signal })
  panel.play.addEventListener(
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
  signal.addEventListener('abort', stop)

  return {
    get activeId() {
      return trip?.id ?? requestedId
    },
    /** Whether playback flies over the route, as last set, for the trip's page to follow. */
    flyover: flyoverMode,
    getTitle: (id: string) => loader.getTitle(id),
    has: (id: string) => loader.has(id),
    /** A trip's playback data, fetched once. */
    load: (id: string) => Promise.resolve(loader.get(id) ?? loader.load(id)),
    prefetch: (id: string) => loader.prefetch(id),
    /** Re-adds the route layers after the map style changes. */
    restoreLayers: () => drawer.addLayers(),
    start,
    stop,
  }
}
