import mapboxgl, { type GeoJSONSource } from 'mapbox-gl'

type TripPlace = {
  latitude: number
  longitude: number
  name: string
}

type TripWaypoint = TripPlace & { date: string; day: number; time?: string }

type TripStop = {
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
  width: number
}

type TripData = {
  dateRange: string
  dayCount: number
  distanceKm: number
  id: string
  pageTitle: string
  photoCount: number
  placeCount: number
  stops: TripStop[]
  title: string
  trailingWaypoints: TripWaypoint[]
  /** Road geometry between consecutive places; null legs are drawn straight. */
  legs: (readonly (readonly [number, number])[] | null)[]
}

type Slide =
  | { type: 'intro' }
  | { stop: TripStop; stopIndex: number; type: 'stop' }
  | { type: 'summary' }

type TripPlayerOptions = {
  getMap: () => mapboxgl.Map | undefined
  onClose: () => void
  page: HTMLElement
  signal: AbortSignal
}

const ROUTE_SOURCE_ID = 'calendar-trip-route'
const PLANNED_SOURCE_ID = 'calendar-trip-planned'
const STOP_SOURCE_ID = 'calendar-trip-stops'
const INTRO_DURATION = 3000
const STOP_DURATION = 5000
const WAYPOINT_PAUSE = 2000
const PHOTO_DURATION = 2000
// Roughly the time it takes to read one Chinese character.
const CHARACTER_DURATION = 120
const IMAGE_SIZES = '(max-width: 48rem) calc(100vw - 3.5rem), 20rem'
const LEG_DURATION = 2200
const STOP_ZOOM = 9
// Each stop is framed together with the trip's other places this close to it.
const NEARBY_RADIUS_KM = 30
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
// Background music fades in at the start and out as the summary card ends.
const MUSIC_FADE_IN = 1
const MUSIC_FADE_OUT = 2
const MUSIC_VOLUME = 0.8
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')

type ExportRatio = 'landscape' | 'portrait'

// Region Capture is Chromium-only and not in the DOM typings yet.
type CroppableTrack = MediaStreamTrack & { cropTo?: (target: unknown) => Promise<void> }
type CropTargetConstructor = { fromElement: (element: Element) => Promise<unknown> }

export function createTripPlayer({ getMap, onClose, page, signal }: TripPlayerOptions) {
  const trips = new Map(parseTripData(page).map((trip) => [trip.id, trip]))
  const panel = queryRequired<HTMLElement>(page, '[data-calendar-map-trip]')
  const progress = queryRequired<HTMLOListElement>(page, '[data-calendar-map-trip-progress]')
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
  let exporting:
    | {
        music?: { context: AudioContext; gain: GainNode; source: AudioBufferSourceNode }
        recorder: MediaRecorder
        stopTimer?: ReturnType<typeof setTimeout>
        stream: MediaStream
      }
    | undefined
  let stopPopups: mapboxgl.Popup[] = []
  let trip: TripData | undefined
  // Every place of every stop in visiting order; the route runs through these.
  let points: TripPoint[] = []
  // Index into `points` just past each stop's last place.
  let stopPointEnds: number[] = []
  let slides: Slide[] = []
  let index = 0
  let elapsed = 0
  let playing = false
  let frame: number | undefined
  let lastFrameTime: number | undefined
  let legFrame: number | undefined
  let finishLeg: (() => void) | undefined
  // Bumped on every slide change so an unfinished trip between waypoints stops.
  let travelToken = 0
  let traveling = false
  let currentWaypointPoint = -1
  let drawnPointCount = 0
  let photoIndex = 0
  let photo: HTMLImageElement | undefined
  let thumbnails: HTMLButtonElement[] = []
  let heightAnimation: Animation | undefined
  // A mouse resting on the card holds the current slide so it can be read.
  let hovering = false

  function getSlideDuration(slide: Slide | undefined) {
    if (slide?.type === 'intro') {
      return INTRO_DURATION
    }

    return slide?.type === 'stop'
      ? Math.max(
          STOP_DURATION,
          slide.stop.images.length * PHOTO_DURATION,
          slide.stop.text.length * CHARACTER_DURATION,
        )
      : undefined
  }

  // The page itself can be a narrow stage while exporting, so measure it, not the window.
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

    for (const id of [STOP_SOURCE_ID, ROUTE_SOURCE_ID, PLANNED_SOURCE_ID]) {
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

  function setRoute(pointCount: number, partial?: [number, number][]) {
    const source = getMap()?.getSource(ROUTE_SOURCE_ID) as GeoJSONSource | undefined

    if (!trip || !source) {
      return
    }

    source.setData(lineFeature([...getRouteLine(0, pointCount), ...(partial ?? [])]))
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
      const t = Math.min((now - startTime) / LEG_DURATION, 1)

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
      setRoute(legStart + 1, [
        ...leg.slice(1, segmentIndex + 1),
        [from[0] + (to[0] - from[0]) * ratio, from[1] + (to[1] - from[1]) * ratio],
      ])
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

  function moveCamera() {
    const map = getMap()
    const slide = slides[index]

    if (!map || !slide) {
      return
    }

    removeStopPopups()

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
      map.flyTo({ ...options, duration: LEG_DURATION, essential: false })
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
        isStageLayout()
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
        duration: LEG_DURATION,
        essential: false,
        padding: getCameraPadding(),
        zoom: STOP_ZOOM,
      })
      // Wait for both the camera and the route to arrive, then pause there.
      await Promise.all([
        drawLeg(Math.max(pointIndex - 1, 0), pointIndex + 1),
        new Promise((resolve) => setTimeout(resolve, reducedMotion.matches ? 0 : LEG_DURATION)),
      ])
      await new Promise((resolve) => setTimeout(resolve, WAYPOINT_PAUSE))

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

  function renderProgress() {
    const duration = getSlideDuration(slides[index])

    for (const [itemIndex, item] of [...progress.children].entries()) {
      const fill = item.firstElementChild as HTMLElement | null

      if (!fill) {
        continue
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
        animateHeight(() => showPhoto(slide.stop, nextPhotoIndex))
      }
    }
  }

  /** Runs a content change and eases the card from its old height to the new one. */
  function animateHeight(update: () => void) {
    const from = panel.hidden ? 0 : panel.offsetHeight

    heightAnimation?.cancel()
    update()

    const to = panel.offsetHeight

    if (!from || from === to || reducedMotion.matches) {
      return
    }

    heightAnimation = panel.animate([{ height: `${from}px` }, { height: `${to}px` }], {
      duration: 280,
      easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
    })
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
          animateHeight(() => showPhoto(stop, imageIndex))
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
        createMosaic(trip),
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
          'block min-h-0 self-center overflow-hidden rounded-md border border-border bg-inline no-underline @max-[48rem]:shrink-0'
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
      stats.className = 'grid shrink-0 grid-cols-2 gap-1 @max-[48rem]:grid-cols-4'

      for (const [value, label] of [
        [trip.dayCount, '天'],
        [trip.placeCount, '个地点'],
        [trip.photoCount, '张照片'],
        [trip.distanceKm.toLocaleString('zh-CN'), '公里'],
      ] as const) {
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
      body.replaceChildren(stats, replay, createMosaic(trip))
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
      reducedMotion.matches ? 0 : LEG_DURATION,
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

    for (const old of frame.querySelectorAll('img')) {
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
  }

  function createIntroCard(currentTrip: TripData) {
    const card = createElement('div', 'calendar-map-trip-stage-card', '')
    const covers = createElement('div', 'calendar-map-trip-stage-card__covers', '')

    for (const cover of currentTrip.stops.flatMap((stop) => stop.images.slice(0, 1)).slice(0, 3)) {
      const image = document.createElement('img')
      image.src = cover.thumbnail
      image.alt = ''
      covers.append(image)
    }

    card.append(
      createElement('p', 'calendar-map-trip-stage-card__eyebrow', '旅程回放'),
      createElement('p', 'calendar-map-trip-stage-card__title', currentTrip.title),
      createElement(
        'p',
        'calendar-map-trip-stage-card__meta',
        `${currentTrip.dateRange} · ${currentTrip.dayCount} 天`,
      ),
      covers,
    )
    return card
  }

  function createSummaryCard(currentTrip: TripData) {
    const card = createElement('div', 'calendar-map-trip-stage-card', '')
    const stats = document.createElement('dl')

    stats.className = 'calendar-map-trip-stage-card__stats'

    for (const [value, label] of [
      [currentTrip.dayCount, '天'],
      [currentTrip.placeCount, '个地点'],
      [currentTrip.photoCount, '张照片'],
      [currentTrip.distanceKm.toLocaleString('zh-CN'), '公里'],
    ] as const) {
      const item = document.createElement('div')
      item.append(createElement('dt', '', label), createElement('dd', '', String(value)))
      stats.append(item)
    }

    card.append(
      createElement('p', 'calendar-map-trip-stage-card__eyebrow', '这趟旅程'),
      createElement('p', 'calendar-map-trip-stage-card__title', currentTrip.title),
      stats,
    )
    return card
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

    const waypointCount =
      slide.type === 'stop'
        ? slide.stop.waypoints.length
        : slide.type === 'summary'
          ? trip.trailingWaypoints.length
          : 0

    if (stepForward && waypointCount > 0) {
      const firstWaypoint =
        slide.type === 'stop' ? getStopPointStart(slide.stopIndex) : (stopPointEnds.at(-1) ?? 0)

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

    index = nextIndex
    elapsed = 0

    if (!getSlideDuration(slide)) {
      playing = false
    }

    animateHeight(renderSlide)

    if (slide.type === 'stop') {
      const legEnd = stopPointEnds[slide.stopIndex]!

      if (legStart === -1) {
        cancelLeg()
        drawnPointCount = legEnd
        setRoute(drawnPointCount)
      } else {
        void drawLeg(legStart ?? Math.max(getStopPointStart(slide.stopIndex) - 1, 0), legEnd)
      }
    } else {
      cancelLeg()
      drawnPointCount = slide.type === 'intro' ? 0 : points.length
      setRoute(drawnPointCount)
    }

    updateStops()
    moveCamera()

    if (exporting && slide.type === 'summary') {
      const music = exporting.music

      if (music) {
        const now = music.context.currentTime
        const fadeStart = now + Math.max(EXPORT_SUMMARY_HOLD / 1000 - MUSIC_FADE_OUT, 0)

        music.gain.gain.setValueAtTime(MUSIC_VOLUME, fadeStart)
        music.gain.gain.linearRampToValueAtTime(0, fadeStart + MUSIC_FADE_OUT)
      }

      exporting.stopTimer = setTimeout(stopExport, EXPORT_SUMMARY_HOLD)
    }
  }

  function setExportOptionsOpen(open: boolean) {
    exportOptions.hidden = !open
    controls.hidden = open
    actions.hidden = open
  }

  /**
   * Records the current tab, cropped to the map stage, while the trip plays once
   * from the start, then downloads the video.
   */
  async function exportVideo(ratio: ExportRatio) {
    const currentTrip = trip

    setExportOptionsOpen(false)

    if (!currentTrip || exporting) {
      return
    }

    let stream: MediaStream

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

    const track = stream.getVideoTracks()[0] as CroppableTrack | undefined
    const cropTarget = (window as Window & { CropTarget?: CropTargetConstructor }).CropTarget

    page.dataset.exportRatio = ratio
    getMap()?.resize()

    try {
      if (track?.cropTo && cropTarget) {
        await track.cropTo(await cropTarget.fromElement(page))
      }
    } catch {
      // Without Region Capture the video includes the whole tab.
    }

    const music = await loadMusic(exportMusic.value)
    const recorded = music
      ? new MediaStream([...stream.getVideoTracks(), ...music.destination.stream.getAudioTracks()])
      : stream
    const mimeType = EXPORT_VIDEO_TYPES.find((type) => MediaRecorder.isTypeSupported(type))
    const recorder = new MediaRecorder(recorded, {
      ...(mimeType ? { mimeType } : {}),
      videoBitsPerSecond: 12_000_000,
    })
    const chunks: Blob[] = []

    recorder.addEventListener('dataavailable', (event) => {
      if (event.data.size > 0) {
        chunks.push(event.data)
      }
    })
    recorder.addEventListener('stop', () => {
      const type = recorder.mimeType || 'video/webm'

      finishExport()
      downloadBlob(
        new Blob(chunks, { type }),
        `${currentTrip.title}-${ratio === 'portrait' ? '竖屏' : '横屏'}.${type.includes('mp4') ? 'mp4' : 'webm'}`,
      )
    })
    track?.addEventListener('ended', stopExport)
    exporting = {
      recorder,
      stream,
      ...(music
        ? { music: { context: music.context, gain: music.gain, source: music.source } }
        : {}),
    }

    // Reset to a still intro first: the camera jumps to the whole route and the
    // tiles load before the first frame, so the video doesn't open mid-flight.
    goTo(0, { play: false })
    fitTrip({ instant: true })
    await waitForMapIdle()

    if (exporting?.recorder !== recorder) {
      return
    }

    recorder.start(1000)

    if (music) {
      const now = music.context.currentTime

      music.gain.gain.setValueAtTime(0, now)
      music.gain.gain.linearRampToValueAtTime(MUSIC_VOLUME, now + MUSIC_FADE_IN)
      music.source.start()
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
        // One more frame so the settled map is what the recording starts on.
        requestAnimationFrame(() => resolve())
      })
      map.triggerRepaint()
    })
  }

  /** Decodes a background track into a looping source routed to a recordable stream. */
  async function loadMusic(src: string) {
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
      const destination = context.createMediaStreamDestination()

      source.buffer = buffer
      source.loop = true
      gain.gain.value = 0
      source.connect(gain)
      gain.connect(destination)
      // Also play it out loud so the recording can be heard while it runs.
      gain.connect(context.destination)
      await context.resume()
      return { context, destination, gain, source }
    } catch {
      // A video without music is better than no video.
      return undefined
    }
  }

  function stopExport() {
    if (exporting?.recorder.state === 'recording') {
      exporting.recorder.stop()
    } else {
      finishExport()
    }
  }

  function finishExport() {
    if (!exporting) {
      return
    }

    clearTimeout(exporting.stopTimer)

    for (const track of exporting.stream.getTracks()) {
      track.stop()
    }

    if (exporting.music) {
      exporting.music.source.stop()
      void exporting.music.context.close()
    }

    exporting = undefined
    delete page.dataset.exportRatio
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
    if (!playing || !duration || document.hidden || traveling || (hovering && !exporting)) {
      return
    }

    elapsed += delta
    renderProgress()

    if (elapsed >= duration) {
      goTo(index + 1)
    }
  }

  function start(id: string) {
    const nextTrip = trips.get(id)

    if (!nextTrip) {
      return
    }

    if (trip?.id === id) {
      return
    }

    stop()
    trip = nextTrip
    const toPoint = (
      place: TripPlace | TripWaypoint,
      stopIndex: number,
      waypoint: boolean,
    ): TripPoint => ({
      coordinates: [place.longitude, place.latitude],
      ...('day' in place ? { eyebrow: getStopEyebrow(place) } : {}),
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
    stopPointEnds = []

    for (const stop of nextTrip.stops) {
      stopPointEnds.push((stopPointEnds.at(-1) ?? 0) + stop.waypoints.length + stop.places.length)
    }

    slides = [
      { type: 'intro' },
      ...nextTrip.stops.map((stop, stopIndex) => ({ stop, stopIndex, type: 'stop' as const })),
      { type: 'summary' },
    ]
    progress.replaceChildren(
      ...slides.map(() => {
        const item = createElement('li', 'h-0.5 flex-1 overflow-hidden rounded-full bg-border', '')
        item.append(createElement('span', 'block h-full origin-left bg-tx [scale:0_1]', ''))
        return item
      }),
    )
    panel.hidden = false
    drawnPointCount = 0
    addLayers()
    index = -1
    goTo(0, { play: !reducedMotion.matches })
    lastFrameTime = undefined
    frame = requestAnimationFrame(tick)
  }

  function stop() {
    if (!trip) {
      return
    }

    stopExport()
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
  exportButton.addEventListener('click', () => setExportOptionsOpen(true), { signal })
  page
    .querySelector('[data-calendar-map-trip-export-cancel]')
    ?.addEventListener('click', () => setExportOptionsOpen(false), { signal })

  for (const button of page.querySelectorAll<HTMLButtonElement>(
    '[data-calendar-map-trip-export-ratio]',
  )) {
    button.addEventListener(
      'click',
      () => void exportVideo(button.dataset.calendarMapTripExportRatio as ExportRatio),
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

      if (exporting && event.key === 'Escape') {
        event.preventDefault()
        stopExport()
        return
      }

      if (event.key === 'ArrowLeft') {
        goTo(index - 1)
      } else if (event.key === 'ArrowRight') {
        goTo(index + 1)
      }
    },
    { signal },
  )
  signal.addEventListener('abort', stop)

  return {
    getPageTitle: (id: string) => trips.get(id)?.pageTitle,
    has: (id: string) => trips.has(id),
    get activeId() {
      return trip?.id
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
function createMosaic(trip: TripData) {
  const covers = trip.stops.flatMap((stop) => stop.images.slice(0, 1)).slice(0, 9)
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

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')

  link.href = url
  link.download = filename
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 60_000)
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

function createElement(tag: string, className: string, text: string) {
  const element = document.createElement(tag)
  element.className = className
  element.textContent = text
  return element
}

function parseTripData(page: HTMLElement): TripData[] {
  try {
    const value: unknown = JSON.parse(
      page.querySelector('[data-calendar-map-trips]')?.textContent ?? '[]',
    )
    return Array.isArray(value) ? (value as TripData[]) : []
  } catch {
    return []
  }
}

function queryRequired<T extends Element>(root: ParentNode, selector: string) {
  const element = root.querySelector<T>(selector)

  if (!element) {
    throw new Error(`Missing required calendar map element: ${selector}`)
  }

  return element
}
