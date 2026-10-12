import mapboxgl, { type Map as MapboxMap } from 'mapbox-gl'
import { overlap, type Rect } from './trip-player/label-placement'

// A picture of the map made for sharing rather than a copy of the screen: the view
// framed on every place, the basemap's lesser names put away, and each place drawn
// with its name and count, under a header with the totals. The view is put back after.
//
// Dots that would sit on one another are nudged apart. Where too many places crowd
// together to be named at all, a picture zoomed in on them is set in an empty corner,
// with the area it shows outlined on the map.

const ATTRIBUTION = '© Mapbox © OpenStreetMap'
// The strip under the map for the site and the attribution, in CSS pixels.
const FOOTER_HEIGHT = 32
// Room around the framed places; the bottom also clears the legend drawn over the map.
const FRAME_PADDING = { bottom: 120, left: 32, right: 32, top: 32 }
const FRAME_MAX_ZOOM = 5
// Between the legend and the bottom of the map, as on the page.
const LEGEND_GAP = 16
const LABEL_HEIGHT = 16
// Kept above the highest place and between the lowest and the legend, when the
// picture is cut down to the band the places are in.
const CROP_MARGIN = 32
// The narrowest the picture is cut to, so the header and footer still fit.
const MIN_CROP_WIDTH = 360
// Between a dot's edge and its label.
const LABEL_GAP = 3
const COUNT_FONT = '11px ui-monospace, monospace'
// Basemap names kept in the picture; towns, roads and the rest give way to the places.
const KEPT_BASEMAP_LABELS = /country|state|continent/
// How long to wait for the tiles before taking the picture anyway.
const IDLE_TIMEOUT = 10_000
// Room kept between dots once they're nudged apart, and the furthest one is moved
// from its place, in CSS pixels: a big crowd stays where it is, overlapping a little.
const DOT_GAP = 1.5
const MAX_NUDGE = 8
// Dots closer than this, edge to edge, count as crowding one another.
const CROWD_GAP = 6
// A crowd this big gets a zoomed-in picture, for at most this many crowds.
const INSET_MIN_PLACES = 6
const MAX_INSETS = 2
// Around the crowd in the zoomed-in picture, and how far in it may zoom.
const INSET_PADDING = 48
const INSET_MAX_ZOOM = 11
// Between the zoomed-in picture and the picture's edges.
const INSET_MARGIN = 12
// An inset shows its crowd at least this many times larger than the map does, or
// it isn't worth its room; and it's kept within these proportions, width to height.
const INSET_MIN_GAIN = 2
const INSET_ASPECT = { max: 1.7, min: 0.6 }

export type ScreenshotPlace = Readonly<{
  color: string
  count: number
  latestYear: number
  latitude: number
  longitude: number
  name: string
}>

export type ScreenshotOptions = Readonly<{
  /** Drawn centred along the bottom of the map. */
  legend?: Element | null | undefined
  places: readonly ScreenshotPlace[]
  site: string
  /** Each a phrase of the header's second line, wrapped between phrases. */
  stats: readonly string[]
  theme: Readonly<{ background: string; muted: string; text: string }>
  title: string
}>

type Label = { count: string; point: PlacePoint; nameWidth: number; rect: Rect }

type PlacePoint = ScreenshotPlace & { x: number; y: number }

/** A view of the map copied off its canvas, with where each place is in it. */
type Shot = { image: HTMLCanvasElement; origin: DOMRect; points: PlacePoint[] }

export async function captureMap(map: MapboxMap, options: ScreenshotOptions) {
  const camera = {
    bearing: map.getBearing(),
    center: map.getCenter(),
    padding: map.getPadding(),
    pitch: map.getPitch(),
    zoom: map.getZoom(),
  }
  const hiddenLabels = hideBasemapLabels(map)

  try {
    frame(map, options.places, FRAME_PADDING, FRAME_MAX_ZOOM)
    await waitForIdle(map)

    const overview = await shoot(map, options.places)
    const crowds: { crowd: PlacePoint[]; shot: Shot }[] = []

    for (const crowd of findCrowds(overview.points).slice(0, MAX_INSETS)) {
      frame(map, crowd, INSET_PADDING, INSET_MAX_ZOOM)
      await waitForIdle(map)
      crowds.push({ crowd, shot: await shoot(map, options.places) })
    }

    return await render(options, overview, crowds)
  } finally {
    for (const id of hiddenLabels) {
      map.setLayoutProperty(id, 'visibility', 'visible')
    }

    map.jumpTo(camera)
  }
}

function hideBasemapLabels(map: MapboxMap) {
  const hidden: string[] = []

  for (const layer of map.getStyle()?.layers ?? []) {
    if (
      layer.type === 'symbol' &&
      !layer.id.startsWith('calendar') &&
      !KEPT_BASEMAP_LABELS.test(layer.id) &&
      map.getLayoutProperty(layer.id, 'visibility') !== 'none'
    ) {
      map.setLayoutProperty(layer.id, 'visibility', 'none')
      hidden.push(layer.id)
    }
  }

  return hidden
}

function frame(
  map: MapboxMap,
  places: readonly ScreenshotPlace[],
  padding: number | typeof FRAME_PADDING,
  maxZoom: number,
) {
  if (places.length === 0) {
    return
  }

  const bounds = new mapboxgl.LngLatBounds()

  for (const place of places) {
    bounds.extend([place.longitude, place.latitude])
  }

  // The drawer's room, if one is open, would push the places aside.
  map.jumpTo({ padding: { bottom: 0, left: 0, right: 0, top: 0 } })

  const camera = map.cameraForBounds(bounds, { maxZoom, padding })

  if (camera) {
    map.jumpTo(camera)
  }
}

function waitForIdle(map: MapboxMap) {
  return new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, IDLE_TIMEOUT)

    map.once('idle', () => {
      clearTimeout(timer)
      resolve()
    })
    map.triggerRepaint()
  })
}

/** The map as it is now, and where each place is on it. */
async function shoot(map: MapboxMap, places: readonly ScreenshotPlace[]): Promise<Shot> {
  const image = await copyCanvas(map)
  const origin = map.getCanvas().getBoundingClientRect()

  return { image, origin, points: projectPlaces(map, places, origin.width, origin.height) }
}

/**
 * The crowds of places big enough for a picture of their own, biggest first: dots
 * that touch or nearly do, and the dots touching those, and so on.
 */
function findCrowds(points: readonly PlacePoint[]) {
  const near = (first: PlacePoint, second: PlacePoint) =>
    Math.hypot(first.x - second.x, first.y - second.y) < radius(first) + radius(second) + CROWD_GAP
  const seen = new Set<PlacePoint>()
  const crowds: PlacePoint[][] = []

  for (const start of points) {
    if (seen.has(start)) {
      continue
    }

    const crowd = [start]
    seen.add(start)

    for (let index = 0; index < crowd.length; index += 1) {
      for (const other of points) {
        if (!seen.has(other) && near(crowd[index]!, other)) {
          seen.add(other)
          crowd.push(other)
        }
      }
    }

    if (crowd.length >= INSET_MIN_PLACES) {
      crowds.push(crowd)
    }
  }

  return crowds.sort((first, second) => second.length - first.length)
}

/**
 * Nudges dots that overlap apart until each shows whole, a pixel or two between them;
 * at a map's scale that moves a place by far less than the distance it stands for.
 * None moves further than MAX_NUDGE, so a crowd too big for that keeps its shape.
 */
function separate(points: PlacePoint[]) {
  const homes = new Map(points.map((point) => [point, { x: point.x, y: point.y }]))
  const tether = (point: PlacePoint) => {
    const home = homes.get(point)!
    const away = Math.hypot(point.x - home.x, point.y - home.y)

    if (away > MAX_NUDGE) {
      point.x = home.x + ((point.x - home.x) / away) * MAX_NUDGE
      point.y = home.y + ((point.y - home.y) / away) * MAX_NUDGE
    }
  }

  for (let round = 0; round < 100; round += 1) {
    let moved = false

    for (const [index, first] of points.entries()) {
      for (const second of points.slice(index + 1)) {
        let dx = second.x - first.x
        let dy = second.y - first.y
        let distance = Math.hypot(dx, dy)
        const least = radius(first) + radius(second) + DOT_GAP

        if (distance >= least) {
          continue
        }

        // Places at the very same spot part along a direction of their own.
        if (distance < 0.01) {
          dx = Math.cos(index + points.indexOf(second))
          dy = Math.sin(index + points.indexOf(second))
          distance = 1
        }

        const push = (least - distance) / 2

        first.x -= (dx / distance) * push
        first.y -= (dy / distance) * push
        second.x += (dx / distance) * push
        second.y += (dy / distance) * push
        tether(first)
        tether(second)
        moved = true
      }
    }

    if (!moved) {
      return
    }
  }
}

/** Copies the canvas in the frame it's drawn (its buffer is cleared once shown). */
function copyCanvas(map: MapboxMap) {
  return new Promise<HTMLCanvasElement>((resolve) => {
    map.once('render', () => {
      const source = map.getCanvas()
      const copy = document.createElement('canvas')

      copy.width = source.width
      copy.height = source.height
      copy.getContext('2d')!.drawImage(source, 0, 0)
      resolve(copy)
    })
    map.triggerRepaint()
  })
}

async function render(
  options: ScreenshotOptions,
  overview: Shot,
  crowds: readonly { crowd: readonly PlacePoint[]; shot: Shot }[],
) {
  const { image: mapImage, origin } = overview
  const scale = mapImage.width / origin.width
  const font = getComputedStyle(document.body).fontFamily
  const output = document.createElement('canvas')
  const context = output.getContext('2d')!
  const width = origin.width
  const legendBox = options.legend?.getBoundingClientRect()
  const legendSize =
    legendBox && legendBox.width > 0
      ? { height: legendBox.height, width: legendBox.width }
      : undefined

  const points = overview.points
  const band = getBand(points, legendSize?.height ?? 0, origin.height)
  const legendTop = band.bottom - LEGEND_GAP - (legendSize?.height ?? 0)
  const minWidth = Math.max(MIN_CROP_WIDTH, (legendSize?.width ?? 0) + CROP_MARGIN * 2)
  // The whole strip the legend goes in, as it's centred once the sides are cut.
  const legendStrip = legendSize && {
    bottom: legendTop + legendSize.height,
    left: 0,
    right: width,
    top: legendTop,
  }
  const areas: Rect[] = []
  const insets: Inset[] = []
  const crowded = new Set<PlacePoint>()

  for (const { crowd, shot } of crowds) {
    const area = padRect(boundsOf(crowd.map(getDot)), 6)
    const inset = layOutInset(context, font, shot, crowd, area, {
      avoid: [area, ...areas, ...insets.map((other) => other.box), ...points.map(getDot)],
      bounds: {
        ...band,
        ...getSides(points, [], minWidth, width),
        bottom: legendStrip ? legendStrip.top - INSET_MARGIN : band.bottom,
      },
      // Two insets stay small enough to sit one above the other.
      mapHeight: (band.bottom - band.top) / (crowds.length > 1 ? 1.25 : 1),
    })

    if (inset) {
      areas.push(area)
      insets.push(inset)

      for (const point of crowd) {
        crowded.add(point)
      }
    }
  }

  // The crowds with an inset stay where they are, outlined, and are named in it.
  separate(points.filter((point) => !crowded.has(point)))

  const labels = placeLabels(
    context,
    points.filter((point) => !crowded.has(point)),
    points,
    font,
    {
      blocked: [
        ...(legendStrip ? [legendStrip] : []),
        ...areas,
        ...insets.map((inset) => inset.box),
      ],
      bounds: { bottom: band.bottom - 4, left: 4, right: width - 4, top: band.top + 4 },
    },
  )
  const crop = {
    ...band,
    ...getSides(
      points,
      labels,
      minWidth,
      width,
      insets.map((inset) => inset.box),
    ),
  }
  const cropWidth = crop.right - crop.left
  const mapHeight = crop.bottom - crop.top

  // The header's lines are measured before the canvas has its size.
  context.font = `12px ${font}`
  const statLines = wrapPhrases(context, options.stats, cropWidth - 32)
  const headerHeight = 16 + 22 + 6 + statLines.length * 18 + 12

  output.width = Math.round(cropWidth * scale)
  output.height = Math.round((headerHeight + mapHeight + FOOTER_HEIGHT) * scale)
  context.setTransform(scale, 0, 0, scale, 0, 0)
  context.fillStyle = options.theme.background
  context.fillRect(0, 0, cropWidth, headerHeight + mapHeight + FOOTER_HEIGHT)

  drawHeader(context, options, statLines, font)

  context.save()
  context.translate(0, headerHeight)
  context.drawImage(
    mapImage,
    crop.left * scale,
    crop.top * scale,
    cropWidth * scale,
    mapHeight * scale,
    0,
    0,
    cropWidth,
    mapHeight,
  )
  context.translate(-crop.left, -crop.top)
  drawPlaces(context, points, labels, options.theme, font)

  for (const [index, inset] of insets.entries()) {
    drawInset(context, inset, areas[index]!, options.theme, font)
  }

  if (options.legend && legendBox && legendSize) {
    context.translate(
      (crop.left + crop.right - legendSize.width) / 2 - legendBox.left,
      legendTop - legendBox.top,
    )
    drawElement(context, options.legend)
  }

  context.restore()
  drawFooter(context, options, cropWidth, headerHeight + mapHeight)

  return new Promise<Blob>((resolve, reject) =>
    output.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Empty capture'))),
      'image/png',
    ),
  )
}

type Inset = {
  box: Rect
  /** The part of the zoomed-in shot shown in `box`, in its CSS pixels. */
  source: Rect
  image: HTMLCanvasElement
  scale: number
  points: PlacePoint[]
  labels: Label[]
}

/**
 * Sizes the zoomed-in picture to the crowd and sets it in the corner of `bounds` it
 * covers least of, then places its dots and names.
 */
function layOutInset(
  context: CanvasRenderingContext2D,
  font: string,
  shot: Shot,
  crowd: readonly PlacePoint[],
  area: Rect,
  view: { avoid: readonly Rect[]; bounds: Rect; mapHeight: number },
): Inset | undefined {
  const spot = (point: PlacePoint) => `${point.longitude},${point.latitude}`
  const crowdSpots = new Set(crowd.map(spot))
  const inShot = shot.points.filter((point) => crowdSpots.has(spot(point)))
  const crowdBox = boundsOf(inShot.map(getDot))
  const source = fitAspect(padRect(crowdBox, INSET_PADDING), shot.origin)
  const sourceWidth = source.right - source.left
  const sourceHeight = source.bottom - source.top
  const boundsWidth = view.bounds.right - view.bounds.left
  const largest = Math.min(
    Math.min(boundsWidth * 0.42, 380) / sourceWidth,
    (view.mapHeight * 0.5) / sourceHeight,
  )
  const { bottom, left, right, top } = view.bounds
  const cornersAt = (fit: number) => {
    const width = sourceWidth * fit
    const height = sourceHeight * fit
    const at = (x: number, y: number) => ({
      fit,
      rect: { bottom: y + height, left: x, right: x + width, top: y },
    })

    return [
      at(right - INSET_MARGIN - width, top + INSET_MARGIN),
      at(left + INSET_MARGIN, top + INSET_MARGIN),
      at(right - INSET_MARGIN - width, bottom - height),
      at(left + INSET_MARGIN, bottom - height),
    ]
  }
  const covered = (rect: Rect) =>
    view.avoid.reduce((total, other) => total + overlap(other, rect), 0)
  // The largest size that fits a corner over no dot, crowd or other inset; failing
  // that, the smallest size in the corner it covers least of.
  const sizes = [1, 0.8, 0.64, 0.5].map((step) => largest * step)
  const { fit, rect: box } =
    sizes.flatMap(cornersAt).find(({ rect }) => covered(rect) === 0) ??
    cornersAt(sizes.at(-1)!).reduce((best, next) =>
      covered(next.rect) < covered(best.rect) ? next : best,
    )

  // Not much bigger than on the map: the crowd is better left nudged apart there.
  if (((crowdBox.right - crowdBox.left) * fit) / (area.right - area.left) < INSET_MIN_GAIN) {
    return undefined
  }
  const points = shot.points
    .filter(
      (point) =>
        point.x >= source.left &&
        point.x <= source.right &&
        point.y >= source.top &&
        point.y <= source.bottom,
    )
    .map((point) => ({
      ...point,
      x: box.left + (point.x - source.left) * fit,
      y: box.top + (point.y - source.top) * fit,
    }))

  separate(points)

  return {
    box,
    image: shot.image,
    labels: placeLabels(context, points, points, font, {
      blocked: [],
      bounds: padRect(box, -4),
    }),
    points,
    scale: shot.image.width / shot.origin.width,
    source,
  }
}

/** The zoomed-in picture in its card, and a line to the area it shows on the map. */
function drawInset(
  context: CanvasRenderingContext2D,
  inset: Inset,
  area: Rect,
  theme: ScreenshotOptions['theme'],
  font: string,
) {
  const { box, source } = inset
  const width = box.right - box.left
  const height = box.bottom - box.top

  context.save()
  context.strokeStyle = theme.muted
  context.lineWidth = 1
  context.setLineDash([3, 3])
  context.beginPath()
  context.roundRect(area.left, area.top, area.right - area.left, area.bottom - area.top, 6)
  // From the side of the area nearest the card to the card.
  const fromX = Math.max(area.left, Math.min(area.right, (box.left + box.right) / 2))
  const fromY = Math.max(area.top, Math.min(area.bottom, (box.top + box.bottom) / 2))
  const toX = Math.max(box.left, Math.min(box.right, fromX))
  const toY = Math.max(box.top, Math.min(box.bottom, fromY))
  context.moveTo(fromX, fromY)
  context.lineTo(toX, toY)
  context.stroke()
  context.restore()

  context.save()
  context.shadowColor = 'rgb(0 0 0 / 0.18)'
  context.shadowBlur = 16
  context.beginPath()
  context.roundRect(box.left, box.top, width, height, 10)
  context.fillStyle = theme.background
  context.fill()
  context.shadowColor = 'transparent'
  context.clip()
  context.drawImage(
    inset.image,
    source.left * inset.scale,
    source.top * inset.scale,
    (source.right - source.left) * inset.scale,
    (source.bottom - source.top) * inset.scale,
    box.left,
    box.top,
    width,
    height,
  )
  drawPlaces(context, inset.points, inset.labels, theme, font)
  context.restore()

  context.save()
  context.strokeStyle = theme.muted
  context.lineWidth = 1
  context.beginPath()
  context.roundRect(box.left + 0.5, box.top + 0.5, width - 1, height - 1, 10)
  context.stroke()
  context.restore()
}

/**
 * Widens or heightens `rect` about its centre to keep within INSET_ASPECT, as far as
 * the shot it's cut from allows.
 */
function fitAspect(rect: Rect, shot: DOMRect): Rect {
  const width = rect.right - rect.left
  const height = rect.bottom - rect.top
  const targetWidth = Math.max(width, height * INSET_ASPECT.min)
  const targetHeight = Math.max(height, width / INSET_ASPECT.max)
  const centreX = (rect.left + rect.right) / 2
  const centreY = (rect.top + rect.bottom) / 2
  const clamp = (start: number, size: number, limit: number) =>
    Math.max(0, Math.min(start, limit - size))
  const left = clamp(centreX - targetWidth / 2, Math.min(targetWidth, shot.width), shot.width)
  const top = clamp(centreY - targetHeight / 2, Math.min(targetHeight, shot.height), shot.height)

  return {
    bottom: top + Math.min(targetHeight, shot.height),
    left,
    right: left + Math.min(targetWidth, shot.width),
    top,
  }
}

function boundsOf(rects: readonly Rect[]): Rect {
  return {
    bottom: Math.max(...rects.map((rect) => rect.bottom)),
    left: Math.min(...rects.map((rect) => rect.left)),
    right: Math.max(...rects.map((rect) => rect.right)),
    top: Math.min(...rects.map((rect) => rect.top)),
  }
}

function padRect(rect: Rect, by: number): Rect {
  return {
    bottom: rect.bottom + by,
    left: rect.left - by,
    right: rect.right + by,
    top: rect.top - by,
  }
}

function drawHeader(
  context: CanvasRenderingContext2D,
  options: ScreenshotOptions,
  statLines: readonly string[],
  font: string,
) {
  context.textAlign = 'left'
  context.textBaseline = 'top'
  context.fillStyle = options.theme.text
  context.font = `600 18px ${font}`
  context.fillText(options.title, 16, 16)
  context.fillStyle = options.theme.muted
  context.font = `12px ${font}`

  for (const [index, line] of statLines.entries()) {
    context.fillText(line, 16, 16 + 22 + 6 + index * 18)
  }
}

/** Joins `phrases` with ` · ` into as few lines as fit `width`. */
function wrapPhrases(context: CanvasRenderingContext2D, phrases: readonly string[], width: number) {
  const lines: string[] = []

  for (const phrase of phrases) {
    const last = lines.at(-1)
    const joined = last === undefined ? phrase : `${last} · ${phrase}`

    if (last !== undefined && context.measureText(joined).width <= width) {
      lines[lines.length - 1] = joined
    } else {
      lines.push(phrase)
    }
  }

  return lines
}

/** The places on screen, where they are on it, busiest first. */
function projectPlaces(
  map: MapboxMap,
  places: readonly ScreenshotPlace[],
  width: number,
  height: number,
): PlacePoint[] {
  return places
    .map((place) => {
      const { x, y } = map.project([place.longitude, place.latitude])
      return { ...place, x, y }
    })
    .filter(({ x, y }) => x >= 0 && x <= width && y >= 0 && y <= height)
    .sort((first, second) => second.count - first.count || first.name.length - second.name.length)
}

function radius(point: PlacePoint) {
  return Math.min(3 + Math.sqrt(point.count) * 1.4, 10)
}

/**
 * The band of the map the places are in, from just above the highest to the legend
 * below the lowest: a phone held upright frames them by width, leaving sky and sea.
 */
function getBand(points: readonly PlacePoint[], legendHeight: number, height: number) {
  if (points.length === 0) {
    return { bottom: height, top: 0 }
  }

  const legendRoom = legendHeight > 0 ? legendHeight + LEGEND_GAP * 2 : 0
  const top =
    Math.min(...points.map((point) => point.y - radius(point))) - LABEL_HEIGHT - CROP_MARGIN
  const bottom =
    Math.max(...points.map((point) => point.y + radius(point))) +
    LABEL_HEIGHT +
    CROP_MARGIN +
    legendRoom

  return { bottom: Math.min(bottom, height), top: Math.max(top, 0) }
}

/**
 * From just left of the leftmost dot or name to just right of the rightmost: a wide
 * screen frames the places by height, leaving land and sea either side. Never
 * narrower than the legend or the header need, and kept on the map.
 */
function getSides(
  points: readonly PlacePoint[],
  labels: readonly Label[],
  minWidth: number,
  width: number,
  extra: readonly Rect[] = [],
) {
  const boxes = [...points.map(getDot), ...labels.map((label) => label.rect), ...extra]

  if (boxes.length === 0) {
    return { left: 0, right: width }
  }

  const left = Math.min(...boxes.map((box) => box.left)) - CROP_MARGIN
  const right = Math.max(...boxes.map((box) => box.right)) + CROP_MARGIN
  const least = Math.min(width, minWidth)
  const grow = Math.max(least - (right - left), 0) / 2
  const shift = Math.max(0 - (left - grow), 0) - Math.max(right + grow - width, 0)

  return {
    left: Math.max(left - grow + shift, 0),
    right: Math.min(right + grow + shift, width),
  }
}

function getDot(point: PlacePoint): Rect {
  const size = radius(point)
  return {
    bottom: point.y + size,
    left: point.x - size,
    right: point.x + size,
    top: point.y - size,
  }
}

/**
 * Names for `named` where they fit, the busiest places first: right beside the
 * place's own dot, so it can't be read as a neighbour's, and over no other dot
 * (of all of `points`), name or `blocked` area. A place with no such spot goes unnamed.
 */
function placeLabels(
  context: CanvasRenderingContext2D,
  named: readonly PlacePoint[],
  points: readonly PlacePoint[],
  font: string,
  view: { blocked: readonly Rect[]; bounds: Rect },
) {
  const dots = points.map(getDot)
  const { bounds } = view
  const labels: Label[] = []

  for (const point of named) {
    const count = String(point.count)

    context.font = `500 12px ${font}`
    const nameWidth = context.measureText(point.name).width
    context.font = COUNT_FONT
    const countWidth = context.measureText(count).width
    const rect = getLabelRects(point, radius(point), nameWidth + 4 + countWidth).find(
      (candidate) =>
        candidate.left >= bounds.left &&
        candidate.right <= bounds.right &&
        candidate.top >= bounds.top &&
        candidate.bottom <= bounds.bottom &&
        !labels.some((label) => overlap(label.rect, candidate) > 0) &&
        !dots.some((dot, index) => points[index] !== point && overlap(dot, candidate) > 0) &&
        !view.blocked.some((rect) => overlap(rect, candidate) > 0),
    )

    if (rect) {
      labels.push({ count, point, nameWidth, rect })
    }
  }

  return labels
}

/** Each place as a dot sized by its Moments, then the names placed for them. */
function drawPlaces(
  context: CanvasRenderingContext2D,
  points: readonly PlacePoint[],
  labels: readonly Label[],
  theme: ScreenshotOptions['theme'],
  font: string,
) {
  context.save()
  context.shadowColor = 'rgb(0 0 0 / 0.25)'
  context.shadowBlur = 4

  for (const point of points) {
    context.beginPath()
    context.arc(point.x, point.y, radius(point), 0, Math.PI * 2)
    context.fillStyle = point.color
    context.fill()
  }

  context.shadowColor = 'transparent'
  context.lineWidth = 1.5
  context.strokeStyle = 'rgb(255 255 255 / 0.95)'

  for (const point of points) {
    context.beginPath()
    context.arc(point.x, point.y, radius(point), 0, Math.PI * 2)
    context.stroke()
  }

  context.restore()

  for (const { count, point, nameWidth, rect } of labels) {
    const middle = (rect.top + rect.bottom) / 2

    context.textBaseline = 'middle'
    context.textAlign = 'left'
    context.lineJoin = 'round'
    context.lineWidth = 3
    context.strokeStyle = theme.background
    context.font = `500 12px ${font}`
    context.strokeText(point.name, rect.left, middle)
    context.fillStyle = theme.text
    context.fillText(point.name, rect.left, middle)
    context.font = COUNT_FONT
    context.strokeText(count, rect.left + nameWidth + 4, middle)
    context.fillStyle = theme.muted
    context.fillText(count, rect.left + nameWidth + 4, middle)
  }
}

/** A label's spots around a dot, best first: to its right, left, above and below. */
function getLabelRects({ x, y }: PlacePoint, radius: number, width: number): Rect[] {
  const offset = radius + LABEL_GAP
  const box = (left: number, top: number) => ({
    bottom: top + LABEL_HEIGHT,
    left,
    right: left + width,
    top,
  })

  return [
    box(x + offset, y - LABEL_HEIGHT / 2),
    box(x - offset - width, y - LABEL_HEIGHT / 2),
    box(x - width / 2, y - offset - LABEL_HEIGHT),
    box(x - width / 2, y + offset),
  ]
}

/**
 * Draws a box and what's in it the way it's laid out: backgrounds, borders and corners,
 * and text where it renders. Enough for the legend's pill, not a renderer.
 */
function drawElement(context: CanvasRenderingContext2D, element: Element) {
  const style = getComputedStyle(element)

  if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) {
    return
  }

  const rect = element.getBoundingClientRect()

  if (rect.width === 0 || rect.height === 0) {
    return
  }

  context.save()
  context.globalAlpha *= Number(style.opacity)

  const radius = Math.min(
    parseFloat(style.borderTopLeftRadius) || 0,
    rect.height / 2,
    rect.width / 2,
  )
  const border = parseFloat(style.borderTopWidth) || 0

  if (isVisibleColor(style.backgroundColor)) {
    context.beginPath()
    context.roundRect(rect.left, rect.top, rect.width, rect.height, radius)
    context.fillStyle = style.backgroundColor
    context.fill()
  }

  if (border > 0 && style.borderTopStyle !== 'none' && isVisibleColor(style.borderTopColor)) {
    context.beginPath()
    context.roundRect(
      rect.left + border / 2,
      rect.top + border / 2,
      rect.width - border,
      rect.height - border,
      Math.max(radius - border / 2, 0),
    )
    context.lineWidth = border
    context.strokeStyle = style.borderTopColor
    context.stroke()
  }

  for (const child of element.childNodes) {
    if (child instanceof Element) {
      drawElement(context, child)
    } else if (child.nodeType === Node.TEXT_NODE && child.textContent?.trim()) {
      const range = document.createRange()
      range.selectNodeContents(child)
      const box = range.getBoundingClientRect()

      context.fillStyle = style.color
      context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
      context.textBaseline = 'middle'
      context.textAlign = 'left'
      context.fillText(child.textContent.trim(), box.left, box.top + box.height / 2)
    }
  }

  context.restore()
}

function drawFooter(
  context: CanvasRenderingContext2D,
  options: ScreenshotOptions,
  width: number,
  top: number,
) {
  const margin = 12
  const middle = top + FOOTER_HEIGHT / 2

  context.font = `500 12px ui-monospace, monospace`
  context.textBaseline = 'middle'
  context.fillStyle = options.theme.muted
  context.textAlign = 'left'
  context.fillText(options.site, margin, middle)
  context.textAlign = 'right'
  context.font = `11px system-ui, sans-serif`
  context.fillText(ATTRIBUTION, width - margin, middle)
}

function isVisibleColor(color: string) {
  return color !== 'transparent' && !/^rgba\(.*,\s*0\)$/.test(color)
}

/**
 * Hands the picture over: the share sheet on a phone, where saving to Photos lives,
 * and a download elsewhere or when sharing isn't possible.
 */
export async function savePicture(blob: Blob, name: string) {
  const file = new File([blob], name, { type: blob.type })

  if (window.matchMedia('(pointer: coarse)').matches && navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file] })
      return
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        return
      }
    }
  }

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')

  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
