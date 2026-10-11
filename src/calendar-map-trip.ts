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
const EXPORT_VIDEO_TYPES = [
  'video/mp4;codecs=avc1',
  'video/mp4',
  'video/webm;codecs=vp9',
  'video/webm',
]
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
  const controls = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-controls]')
  const actions = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-actions]')
  const exportButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-export]')
  const exportOptions = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-export-options]')
  let exporting:
    | { recorder: MediaRecorder; stopTimer?: ReturnType<typeof setTimeout>; stream: MediaStream }
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

  function getCameraPadding() {
    if (isNarrow()) {
      return { bottom: panel.offsetHeight + 40, left: 40, right: 40, top: 120 }
    }

    return { bottom: 64, left: panel.offsetWidth + 64, right: 64, top: 96 }
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
      lineFeature(points.map((point) => point.coordinates)),
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

  function setRoute(pointCount: number, partial?: [number, number]) {
    const source = getMap()?.getSource(ROUTE_SOURCE_ID) as GeoJSONSource | undefined

    if (!trip || !source) {
      return
    }

    const coordinates = points.slice(0, pointCount).map((point) => point.coordinates)

    if (partial) {
      coordinates.push(partial)
    }

    source.setData(lineFeature(coordinates))
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
    const leg = points.slice(legStart, legEnd).map((point) => point.coordinates)
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

      setRoute(legStart + segmentIndex + 1, [
        from[0] + (to[0] - from[0]) * ratio,
        from[1] + (to[1] - from[1]) * ratio,
      ])
      legFrame = requestAnimationFrame(step)
    }

    legFrame = requestAnimationFrame(step)
  }

  function fitTrip() {
    const map = getMap()

    if (!map || !trip) {
      return
    }

    const bounds = new mapboxgl.LngLatBounds()

    for (const point of points) {
      bounds.extend(point.coordinates)
    }

    map.fitBounds(bounds, {
      duration: reducedMotion.matches ? 0 : 1200,
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

    // The first place carries the day and time; the card itself shows no title.
    showPopups(
      places.map((place, placeIndex) => ({
        coordinates: [place.longitude, place.latitude],
        name: place.name,
        ...(placeIndex === 0 ? { eyebrow: getStopEyebrow(slide.stop) } : {}),
      })),
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
        anchor: isNarrow() ? 'bottom' : 'bottom-left',
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

    for (let pointIndex = first; pointIndex < first + count; pointIndex += 1) {
      const point = points[pointIndex]!

      currentWaypointPoint = pointIndex
      updateStops()
      showPopups([
        {
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
        'max-h-[min(20dvh,9rem)] shrink-0 overflow-y-auto overscroll-contain [scrollbar-width:none] break-words whitespace-pre-line text-tx-2 data-[more]:[mask-image:linear-gradient(to_bottom,#000_calc(100%-2.5rem),transparent)] [&::-webkit-scrollbar]:hidden',
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
          'mt-3 block min-h-0 self-center overflow-hidden rounded-md border border-border bg-inline no-underline'
        link.setAttribute('aria-label', `查看 ${stop.date}的记录`)
        photo.className = 'block h-auto max-h-[min(40dvh,22rem)] w-auto max-w-full'
        photo.sizes = IMAGE_SIZES
        photo.decoding = 'async'
        link.append(photo)
      } else {
        link.className = 'mt-3 self-start text-[0.8125rem] text-tx-2 hover:text-tx'
        link.textContent = '查看当天记录'
      }

      const syncTextFade = () => {
        text.toggleAttribute(
          'data-more',
          text.scrollTop + text.clientHeight < text.scrollHeight - 1,
        )
      }

      text.addEventListener('scroll', syncTextFade, { passive: true, signal })
      body.replaceChildren(
        ...(stop.text ? [text] : []),
        link,
        ...(stop.images.length > 1 ? [createThumbnails(stop)] : []),
      )
      showPhoto(stop, 0)
      syncTextFade()

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
      stats.className = 'grid shrink-0 grid-cols-2 gap-1'

      for (const [value, label] of [
        [trip.dayCount, '天'],
        [trip.placeCount, '个地点'],
        [trip.photoCount, '张照片'],
        [trip.distanceKm.toLocaleString('zh-CN'), '公里（直线）'],
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

    renderProgress()
    renderPlayButton()
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

    const mimeType = EXPORT_VIDEO_TYPES.find((type) => MediaRecorder.isTypeSupported(type))
    const recorder = new MediaRecorder(stream, {
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
    exporting = { recorder, stream }

    // Let the map settle at its new size before the first frame.
    await new Promise((resolve) => setTimeout(resolve, 1000))

    if (exporting?.recorder !== recorder) {
      return
    }

    recorder.start(1000)
    goTo(0, { play: true })
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

    exporting = undefined
    delete page.dataset.exportRatio
    getMap()?.resize()
  }

  function tick(now: number) {
    frame = requestAnimationFrame(tick)

    const delta = lastFrameTime === undefined ? 0 : now - lastFrameTime
    const duration = getSlideDuration(slides[index])

    lastFrameTime = now

    // The card's timer waits while the map is still travelling between waypoints.
    if (!playing || !duration || document.hidden || traveling) {
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
  const mosaic = createElement('div', 'mt-3 grid shrink-0 grid-cols-3 gap-1', '')

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
