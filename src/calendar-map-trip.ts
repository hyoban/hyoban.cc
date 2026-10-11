import mapboxgl, { type GeoJSONSource } from 'mapbox-gl'

type TripStop = {
  date: string
  day: number
  href: string
  images: TripImage[]
  latitude: number
  longitude: number
  name: string
  text: string
  time?: string
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
  photoCount: number
  placeCount: number
  stops: TripStop[]
  title: string
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
const PHOTO_DURATION = 2000
// Roughly the time it takes to read one Chinese character.
const CHARACTER_DURATION = 120
const IMAGE_SIZES = '(max-width: 48rem) calc(100vw - 3.5rem), 20rem'
const LEG_DURATION = 1600
const STOP_ZOOM = 9
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')

export function createTripPlayer({ getMap, onClose, page, signal }: TripPlayerOptions) {
  const trips = new Map(parseTripData(page).map((trip) => [trip.id, trip]))
  const panel = queryRequired<HTMLElement>(page, '[data-calendar-map-trip]')
  const progress = queryRequired<HTMLOListElement>(page, '[data-calendar-map-trip-progress]')
  const eyebrow = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-eyebrow]')
  const title = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-title]')
  const body = queryRequired<HTMLElement>(page, '[data-calendar-map-trip-body]')
  const previousButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-previous]')
  const nextButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-next]')
  const playButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-play]')
  const closeButton = queryRequired<HTMLButtonElement>(page, '[data-calendar-map-trip-close]')
  const stopPopup = new mapboxgl.Popup({
    closeButton: false,
    closeOnClick: false,
    focusAfterOpen: false,
    offset: 12,
  })
  let trip: TripData | undefined
  let slides: Slide[] = []
  let index = 0
  let elapsed = 0
  let playing = false
  let frame: number | undefined
  let lastFrameTime: number | undefined
  let legFrame: number | undefined
  let drawnStopCount = 0
  let photoIndex = 0
  let photo: HTMLImageElement | undefined
  let thumbnails: HTMLButtonElement[] = []

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

  function getCameraPadding() {
    if (window.matchMedia('(max-width: 48rem)').matches) {
      return { bottom: panel.offsetHeight + 40, left: 40, right: 40, top: 120 }
    }

    return { bottom: 64, left: panel.offsetWidth + 64, right: 64, top: 96 }
  }

  function getCoordinates(stop: TripStop): [number, number] {
    return [stop.longitude, stop.latitude]
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
        'circle-radius': ['match', ['get', 'state'], 'current', 7, 4.5],
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
      lineFeature(trip.stops.map(getCoordinates)),
    )
    setRoute(drawnStopCount)
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

  function setRoute(stopCount: number, partial?: [number, number]) {
    const source = getMap()?.getSource(ROUTE_SOURCE_ID) as GeoJSONSource | undefined

    if (!trip || !source) {
      return
    }

    const coordinates = trip.stops.slice(0, stopCount).map(getCoordinates)

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
      features: trip.stops.map((stop, stopIndex) => ({
        geometry: { coordinates: getCoordinates(stop), type: 'Point' as const },
        properties: {
          state:
            stopIndex === currentStopIndex
              ? 'current'
              : stopIndex < drawnStopCount
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
  }

  function drawLeg(stopIndex: number) {
    cancelLeg()

    const from = trip?.stops[stopIndex - 1]
    const to = trip?.stops[stopIndex]

    if (!from || !to || reducedMotion.matches) {
      drawnStopCount = stopIndex + 1
      setRoute(drawnStopCount)
      return
    }

    drawnStopCount = stopIndex
    const startTime = performance.now()
    const step = (now: number) => {
      const t = Math.min((now - startTime) / LEG_DURATION, 1)
      const eased = 1 - (1 - t) ** 3

      if (t >= 1) {
        legFrame = undefined
        drawnStopCount = stopIndex + 1
        setRoute(drawnStopCount)
        updateStops()
        return
      }

      setRoute(stopIndex, [
        from.longitude + (to.longitude - from.longitude) * eased,
        from.latitude + (to.latitude - from.latitude) * eased,
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

    for (const stop of trip.stops) {
      bounds.extend(getCoordinates(stop))
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

    if (slide.type !== 'stop') {
      stopPopup.remove()
      fitTrip()
      return
    }

    const options = {
      center: getCoordinates(slide.stop),
      padding: getCameraPadding(),
      zoom: Math.max(map.getZoom(), STOP_ZOOM),
    }

    stopPopup
      .setLngLat(options.center)
      .setText(slide.stop.time ? `${slide.stop.name} · ${slide.stop.time}` : slide.stop.name)
      .addTo(map)

    if (reducedMotion.matches) {
      map.jumpTo(options)
    } else {
      map.flyTo({ ...options, duration: LEG_DURATION, essential: false, zoom: STOP_ZOOM })
    }
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
        showPhoto(slide.stop, nextPhotoIndex)
      }
    }
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
      'mt-1 flex gap-1 overflow-x-auto overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
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
          showPhoto(stop, imageIndex)
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

    previousButton.disabled = index === 0
    nextButton.disabled = index === slides.length - 1

    if (slide.type === 'intro') {
      eyebrow.textContent = '旅程回放'
      title.textContent = trip.title
      body.replaceChildren(
        createElement(
          'p',
          'text-tx-2',
          `${trip.dateRange} · ${trip.dayCount} 天 · ${trip.stops.length} 条记录`,
        ),
      )
    } else if (slide.type === 'stop') {
      const { stop } = slide
      const text = createElement(
        'p',
        'max-h-[min(20dvh,9rem)] overflow-y-auto overscroll-contain [scrollbar-width:none] break-words whitespace-pre-line text-tx-2 data-[more]:[mask-image:linear-gradient(to_bottom,#000_calc(100%-2.5rem),transparent)] [&::-webkit-scrollbar]:hidden',
        stop.text,
      )
      const link = document.createElement('a')

      eyebrow.textContent = [`第 ${stop.day} 天`, stop.date, stop.time].filter(Boolean).join(' · ')
      title.textContent = stop.name
      link.href = stop.href

      photo = undefined
      thumbnails = []
      photoIndex = 0

      if (stop.images.length > 0) {
        photo = document.createElement('img')
        link.className =
          'mt-3 block overflow-hidden rounded-md border border-border bg-inline no-underline'
        link.setAttribute('aria-label', `查看 ${stop.date}的记录`)
        photo.className = 'block h-[min(30dvh,15rem)] w-full object-cover'
        photo.sizes = IMAGE_SIZES
        photo.decoding = 'async'
        link.append(photo)
      } else {
        link.className = 'mt-3 inline-block text-[0.8125rem] text-tx-2 hover:text-tx'
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
        'mt-3 inline-flex min-h-9 cursor-pointer items-center gap-1.5 rounded-full border border-border bg-transparent px-3.5 text-[0.8125rem] text-tx hover:bg-inline',
        '重新播放',
      ) as HTMLButtonElement
      const replayIcon = createElement('span', 'i-lucide-rotate-ccw', '')

      eyebrow.textContent = '旅程回顾'
      title.textContent = trip.title
      stats.className = 'grid grid-cols-2 gap-1'

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
      body.replaceChildren(stats, replay)
    }

    renderProgress()
    renderPlayButton()
  }

  function goTo(nextIndex: number, options: { play?: boolean } = {}) {
    if (!trip || nextIndex < 0 || nextIndex >= slides.length) {
      return
    }

    const previousIndex = index
    const slide = slides[nextIndex]!

    index = nextIndex
    elapsed = 0

    if (options.play !== undefined) {
      playing = options.play
    }

    if (!getSlideDuration(slide)) {
      playing = false
    }

    renderSlide()

    if (slide.type === 'stop') {
      if (nextIndex === previousIndex + 1) {
        drawLeg(slide.stopIndex)
      } else {
        cancelLeg()
        drawnStopCount = slide.stopIndex + 1
        setRoute(drawnStopCount)
      }
    } else {
      cancelLeg()
      drawnStopCount = slide.type === 'intro' ? 0 : trip.stops.length
      setRoute(drawnStopCount)
    }

    updateStops()
    moveCamera()
  }

  function tick(now: number) {
    frame = requestAnimationFrame(tick)

    const delta = lastFrameTime === undefined ? 0 : now - lastFrameTime
    const duration = getSlideDuration(slides[index])

    lastFrameTime = now

    if (!playing || !duration || document.hidden) {
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
    drawnStopCount = 0
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

    if (frame !== undefined) {
      cancelAnimationFrame(frame)
      frame = undefined
    }

    cancelLeg()
    stopPopup.remove()
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
