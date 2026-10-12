import type { Map as MapboxMap } from 'mapbox-gl'
import type { PanelElements, StageElements } from '../elements.ts'
import { formatDate } from '../format.ts'
import { t, type Messages } from '../messages.ts'
import { createDayHeading } from './day-heading.ts'
import { downloadBlob } from './dom.ts'
import { createChapterLog } from './labels.ts'
import { closeMusic, fadeMusicIn, fadeMusicOut, loadMusic, type Music } from './music.ts'
import {
  readRatioPreference,
  readSafeAreaPreference,
  writeRatioPreference,
  writeSafeAreaPreference,
} from './preferences.ts'
import {
  canRecord,
  createRecording,
  cropTo,
  releaseCapture,
  requestTabCapture,
  type Recording,
} from './recorder.ts'
import { getClock } from './slides.ts'
import { createIntroCard, createSummaryCard } from './stage-cards.ts'
import { createStagePhotoCard } from './stage-photo-card.ts'
import type { Slide, TripData, TripImage, TripPoint } from './types.ts'
import { createScreenWakeLock } from './wake-lock.ts'

// The story layout: the same playback run full screen to present, or captured and
// recorded to a video. The page becomes a fixed-ratio stage with a heading, a column
// of photo cards and a progress bar; this owns that layout, its settings and the
// recording, and tells the player when to start over and when it has finished.

export type ExportRatio = 'landscape' | 'portrait'
/** The story layout either plays on screen or is recorded to a video file. */
export type StageMode = 'present' | 'record'

// How long the summary card stays in an exported video before it ends.
const EXPORT_SUMMARY_HOLD = 4000
// How long the pointer rests before the presentation's exit button fades away.
const CONTROLS_IDLE = 2500

export type Stage = ReturnType<typeof createStage>

export function createStage({
  elements,
  frameTrip,
  getMap,
  getPoints,
  getSlides,
  getSpeed,
  getTrip,
  goTo,
  isMusicOn,
  messages,
  onFinish,
  onStart,
  page,
  panel,
  setSpeed,
  signal,
  siteLine,
  waitForMotion,
}: {
  elements: StageElements
  /** Frames the whole route, at once, for the still the recording opens on. */
  frameTrip: (options: { instant: boolean }) => void
  getMap: () => MapboxMap | undefined
  getPoints: () => readonly TripPoint[]
  getSlides: () => readonly Slide[]
  getSpeed: () => number
  getTrip: () => TripData | undefined
  goTo: (index: number, options: { play: boolean }) => void
  isMusicOn: () => boolean
  messages: Messages
  /** The stage came down: the side card takes over for whatever slide is showing. */
  onFinish: () => void
  /** Playback starts from the top. */
  onStart: () => void
  page: HTMLElement
  panel: PanelElements
  setSpeed: (speed: number) => void
  signal: AbortSignal
  /** Where a video's viewers can find the trips, on the summary card. */
  siteLine: string
  /** Resolves once `duration` milliseconds of motion have passed, not counting holds. */
  waitForMotion: (duration: number) => Promise<void>
}) {
  const heading = createDayHeading(elements.eyebrow, elements.title, messages)
  const photoCard = createStagePhotoCard(elements.photos, messages)
  // The screen stays on through a presentation or a recording.
  const wakeLock = createScreenWakeLock(signal)
  let safeArea = readSafeAreaPreference()
  let ratio: ExportRatio = readRatioPreference()
  let mode: StageMode = 'record'
  let staging:
    | {
        mode: StageMode
        music?: Music
        recording?: Recording
        stopTimer?: ReturnType<typeof setTimeout>
      }
    | undefined
  // The stage running or starting, and the speed to go back to after a recording,
  // which always plays at 1×.
  let active: { mode: StageMode; speed: number } | undefined
  let token: object | undefined
  // Where each day starts in the video being recorded, for the description's chapters.
  let chapters: { log: ReturnType<typeof createChapterLog>; start: number } | undefined
  // Bumped whenever the stage photo card's pending pop-up is called off.
  let photoToken = 0
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  // Photos decoded ahead while recording, so a card never opens on a blank frame.
  const decodedPhotos = new Map<string, Promise<void>>()
  const readyPhotos = new Set<string>()

  /** Presenting or recording, a story-style overlay replaces the side card. */
  function isStageLayout() {
    return page.dataset.tripMapRatio !== undefined
  }

  /** The camera's padding for the stage's heading and cards. */
  function getPadding() {
    const pageRect = page.getBoundingClientRect()
    // Room for the header plus a two-line label above the topmost place.
    const top = elements.header.getBoundingClientRect().bottom - pageRect.top + 72
    const photos = elements.photos.getBoundingClientRect()
    const safe = elements.safe.getBoundingClientRect()

    return page.dataset.tripMapRatio === 'landscape'
      ? { bottom: 64, left: 96, right: pageRect.right - photos.left + 96, top }
      : {
          bottom: pageRect.bottom - photos.top + 16,
          left: safe.left - pageRect.left + 48,
          right: pageRect.right - safe.right + 48,
          top,
        }
  }

  /** The boxes over the map that place labels keep out from under. */
  function getBlocked() {
    const shown = (element: Element | null | undefined) =>
      element &&
      element.getClientRects().length > 0 &&
      getComputedStyle(element).visibility !== 'hidden'
        ? [element.getBoundingClientRect()]
        : []

    return [
      ...outside(elements.safe.getBoundingClientRect(), page.getBoundingClientRect()),
      ...shown(elements.header),
      ...[...elements.photos.children].flatMap(shown),
      ...shown(elements.progress),
      ...shown(elements.close),
    ]
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

  /** Shows the stage's card for a slide: the intro, the summary or a stop's photo card. */
  function render(
    slide: Slide,
    {
      covers,
      photoDelay,
      photoIndex,
    }: { covers: readonly TripImage[]; photoDelay: number; photoIndex: number },
  ) {
    const trip = getTrip()

    photoToken += 1

    // The progress bar is shared: on the stage it runs along the bottom.
    if (!trip || !isStageLayout()) {
      if (panel.progress.parentElement !== panel.panel) {
        panel.panel.prepend(panel.progress)
      }

      return
    }

    if (panel.progress.parentElement !== elements.progress) {
      elements.progress.append(panel.progress)
    }

    elements.stage.dataset.slide = slide.type
    photoCard.clear()

    if (slide.type === 'intro') {
      elements.center.replaceChildren(createIntroCard(trip, covers, messages))
      return
    }

    if (slide.type === 'summary') {
      elements.center.replaceChildren(createSummaryCard(trip, siteLine, messages))
      return
    }

    // Presenting or recording skips a day's opener, so a stop is all that's left.
    if (slide.type !== 'stop') {
      return
    }

    const { stop } = slide

    elements.center.replaceChildren()
    // The photo card pops up once the camera has arrived at the stop.
    const pending = photoToken

    void waitForMotion(photoDelay / getSpeed()).then(() => {
      if (pending === photoToken) {
        photoCard.show(stop, slide.stopIndex)
        photoCard.showPhoto(stop, photoIndex)
      }
    })
  }

  /**
   * Notes in the recording where each day starts (the stop after its skipped opener)
   * and where the summary does.
   */
  function addChapter(slideIndex: number) {
    const slides = getSlides()
    const previous = slides[slideIndex - 1]
    const slide = previous?.type === 'day' ? previous : slides[slideIndex]

    if (chapters && (slide?.type === 'day' || slide?.type === 'summary')) {
      chapters.log.add(
        slide.type === 'day'
          ? `${t(messages, 'dayLabel', { day: slide.day })}${messages.separator}${formatDate(slide.dateKey, messages.locale, { weekday: true })}`
          : messages.summary,
        Math.floor((performance.now() - chapters.start) / 1000),
      )
    }
  }

  /** A recording ends a moment after the summary; a presentation stays on it, its music fading out. */
  function onSummary() {
    if (!staging) {
      return
    }

    if (staging.music) {
      fadeMusicOut(staging.music, EXPORT_SUMMARY_HOLD / 1000)
    }

    if (staging.recording) {
      staging.stopTimer = setTimeout(stop, EXPORT_SUMMARY_HOLD)
    }
  }

  /**
   * Shows the presentation's exit button and pointer, then hides both again once the
   * pointer has rested for a while, so they don't sit on the picture.
   */
  function wakeControls() {
    clearTimeout(idleTimer)
    page.removeAttribute('data-trip-map-idle')

    if (page.hasAttribute('data-trip-map-presenting')) {
      idleTimer = setTimeout(() => page.setAttribute('data-trip-map-idle', ''), CONTROLS_IDLE)
    }
  }

  /** Opens the settings for presenting or recording in place of the footer buttons. */
  function openSetup(nextMode: StageMode) {
    setSetupOpen(true, nextMode)
  }

  function closeSetup() {
    setSetupOpen(false)
  }

  function setSetupOpen(open: boolean, nextMode: StageMode = mode) {
    mode = nextMode
    panel.setupTitle.textContent = mode === 'present' ? messages.present : messages.exportVideo
    panel.setupStart.textContent = mode === 'present' ? messages.presentStart : messages.recordStart
    renderSetup()
    panel.setup.hidden = !open
    panel.controls.hidden = open
    panel.actions.hidden = open

    if (open) {
      panel.setupStart.focus({ preventScroll: true })
    }
  }

  function renderSetup() {
    for (const button of panel.ratioButtons) {
      button.setAttribute('aria-checked', String(button.dataset.tripMapSetupRatio === ratio))
    }

    panel.setupSafeArea.checked = safeArea
    // Margins for a short-video app's overlays only matter for a portrait video.
    panel.setupSafeAreaRow.hidden = mode !== 'record' || ratio !== 'portrait'
  }

  /**
   * Plays the trip once from the start in the story layout. Recording captures the
   * tab, cropped to the stage, and downloads the video; presenting goes full screen.
   */
  async function start(nextRatio: ExportRatio, nextMode: StageMode) {
    const trip = getTrip()

    setSetupOpen(false)

    if (!trip || staging || token) {
      return
    }

    // Both requests need the click that started this, so they come first.
    const stream = nextMode === 'record' ? await requestTabCapture() : undefined

    if (nextMode === 'record' && !stream) {
      return
    }

    if (nextMode === 'present') {
      // Full screen is a nicety; some browsers never settle the request, so don't wait.
      void document.documentElement.requestFullscreen?.().catch(() => {})
    }

    const current = {}
    token = current
    active = { mode: nextMode, speed: getSpeed() }
    wakeLock.hold()

    if (nextMode === 'record') {
      setSpeed(1)
    }

    // The heading is up from the start, so it opens on the trip's first moment.
    heading.set(getClock(getPoints(), 0), { animate: false })
    page.dataset.tripMapRatio = nextRatio
    page.toggleAttribute('data-trip-map-presenting', nextMode === 'present')
    page.toggleAttribute(
      'data-trip-map-safe-area',
      safeArea && nextMode === 'record' && nextRatio === 'portrait',
    )
    wakeControls()
    getMap()?.resize()

    if (stream) {
      await cropTo(stream, page)
    }

    const music = await loadMusic(panel.setupMusic.value, {
      audible: isMusicOn(),
      record: nextMode === 'record',
    })

    // Stopped while the music loaded: let go of what was opened for it.
    if (token !== current) {
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
          const name = `${trip.title}-${nextRatio === 'portrait' ? messages.portraitFile : messages.landscapeFile}`
          const chapterText = chapters?.log.toString()

          finish()
          downloadBlob(video, `${name}.${extension}`)

          if (chapterText) {
            downloadBlob(
              new Blob([chapterText], { type: 'text/plain' }),
              t(messages, 'chaptersFile', { name }),
            )
          }
        },
      )

    // Ending the capture from the browser's own bar ends the recording.
    stream?.getVideoTracks()[0]?.addEventListener('ended', stop)
    staging = {
      mode: nextMode,
      ...(music ? { music } : {}),
      ...(recording ? { recording } : {}),
    }

    // Reset to a still intro first: the camera jumps to the whole route and the
    // tiles load before the first frame, so playback doesn't open mid-flight.
    goTo(0, { play: false })
    frameTrip({ instant: true })
    await waitForMapIdle()
    await recording?.ready

    if (token !== current) {
      return
    }

    if (recording) {
      recording.start()
      chapters = { log: createChapterLog(trip.title), start: performance.now() }
    }

    if (music) {
      fadeMusicIn(music)
    }

    onStart()
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

  function stop() {
    if (staging?.recording?.active) {
      staging.recording.stop()
    } else {
      finish()
    }
  }

  function finish() {
    // Still starting (loading its music), the stage has nothing of its own to release
    // yet, but its layout is already up.
    const starting = token !== undefined && !staging

    token = undefined
    chapters = undefined
    wakeLock.release()

    if (!staging && !starting) {
      return
    }

    const presenting = page.hasAttribute('data-trip-map-presenting')

    if (staging) {
      clearTimeout(staging.stopTimer)
      staging.recording?.release()

      if (staging.music) {
        closeMusic(staging.music)
      }
    }

    staging = undefined

    if (active) {
      setSpeed(active.speed)
      active = undefined
    }

    delete page.dataset.tripMapRatio
    page.removeAttribute('data-trip-map-safe-area')
    page.removeAttribute('data-trip-map-presenting')
    clearTimeout(idleTimer)
    page.removeAttribute('data-trip-map-idle')

    if (presenting && document.fullscreenElement) {
      void document.exitFullscreen().catch(() => {})
    }

    getMap()?.resize()
    // Back to the side card for whatever slide is showing.
    onFinish()
  }

  /** Starts decoding the photos of the first stop after slide `index`. */
  function preparePhotosAhead(index: number) {
    const next = getSlides()
      .slice(index + 1)
      .find((slide) => slide.type === 'stop')

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

  /** Whether the slide after `index` can show: a stop's first photo has to be ready to record. */
  function isNextSlideReady(index: number) {
    const next = getSlides()[index + 1]
    const first = next?.type === 'stop' ? next.stop.images[0] : undefined

    if (!staging?.recording || !first || readyPhotos.has(first.src)) {
      return true
    }

    preparePhotosAhead(index)
    return false
  }

  panel.exportButton.hidden = !canRecord()
  panel.exportButton.addEventListener('click', () => openSetup('record'), { signal })
  panel.present.addEventListener('click', () => openSetup('present'), { signal })
  panel.setupCancel.addEventListener('click', closeSetup, { signal })
  panel.setupStart.addEventListener('click', () => void start(ratio, mode), { signal })
  panel.setupSafeArea.addEventListener(
    'change',
    () => {
      safeArea = panel.setupSafeArea.checked
      writeSafeAreaPreference(safeArea)
    },
    { signal },
  )

  for (const button of panel.ratioButtons) {
    button.addEventListener(
      'click',
      () => {
        ratio = button.dataset.tripMapSetupRatio as ExportRatio
        writeRatioPreference(ratio)
        renderSetup()
      },
      { signal },
    )
  }

  elements.close.addEventListener('click', stop, { signal })
  page.addEventListener('pointermove', wakeControls, { signal })
  page.addEventListener('pointerdown', wakeControls, { signal })
  document.addEventListener(
    'fullscreenchange',
    () => {
      // Leaving full screen (Esc, or the browser's own control) ends a presentation.
      if (staging?.mode === 'present' && !document.fullscreenElement) {
        stop()
      }
    },
    { signal },
  )

  return {
    get active() {
      return active
    },
    addChapter,
    closeSetup,
    finish,
    getBlocked,
    getPadding,
    heading,
    isNextSlideReady,
    isStageLayout,
    /** Whether the presenting/recording settings are open. */
    get setupOpen() {
      return !panel.setup.hidden
    },
    onSummary,
    openSetup,
    photoCard,
    preparePhotosAhead,
    /** Whether a stage is up or on its way up. */
    get pending() {
      return token !== undefined
    },
    render,
    get staging() {
      return staging
    },
    start,
    stop,
    wakeControls,
  }
}
