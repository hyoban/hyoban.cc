import mapboxgl, { type Map as MapboxMap } from 'mapbox-gl'
import { overlap, type Rect } from '../player/label-placement.ts'
import { hideBasemapLabels } from './basemap.ts'

// A picture of the map made for sharing rather than a copy of the screen: the view
// framed on every place, the basemap's lesser names put away, and each place drawn
// with its name and count, under a header with the totals. The view is put back after.
//
// Places are gathered as the map gathers them there: near ones into one bubble with
// their Moments, named after the busiest. The bubbles get magnifiers, pictures zoomed
// in on their places, each set in the free spot nearest them with the area it shows
// outlined on the map. Dots may overlap a little, and a name with no room is left off.

const ATTRIBUTION = '© Mapbox © OpenStreetMap'
// The map is drawn at this size, in CSS pixels, whatever the screen: the picture is
// always the same landscape one, a phone's only sharper for its denser pixels.
const SHOT_SIZE = { height: 780, width: 1100 }
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
// Between a dot's edge and its label, and how much further out a name goes, with a
// line to its dot, when there's no room beside it.
const LABEL_GAP = 3
const LEADER_LENGTH = 14
const COUNT_FONT = '11px ui-monospace, monospace'
// Basemap names kept in the picture; towns, roads and the rest give way to the places.
const KEPT_BASEMAP_LABELS = /country|state|continent/
// How long to wait for the tiles before taking the picture anyway.
const IDLE_TIMEOUT = 10_000
// Every bubble gets a magnifier, up to this many; bubbles near one another share one
// while together they hold no more than this many places.
const MAX_INSETS = 4
const INSET_MAX_PLACES = 18
const INSET_GROUP_GAP = 40
// How large a magnifier may be, against the map, and the step between the spots it
// may take while looking for the free one nearest the places it shows.
const INSET_MAX_SHARE = { height: 0.45, width: 0.36 }
const INSET_MAX_WIDTH = 340
const INSET_STEP = 12
// Around the places in a magnifier, and how far in it may zoom.
const INSET_PADDING = 48
const INSET_MAX_ZOOM = 11
// Between a magnifier and the picture's edges.
const INSET_MARGIN = 12
// A magnifier shows its places at least this many times larger than the map does, or
// it isn't worth its room; and it's kept within these proportions, width to height.
const INSET_MIN_GAIN = 2
const INSET_ASPECT = { max: 1.7, min: 0.6 }

export type ScreenshotPlace = Readonly<{
  color: string
  /** Moments there; for a bubble, at all its places. */
  count: number
  firstYear: number
  latitude: number
  longitude: number
  /** A bubble's places, busiest first. */
  members?: readonly ScreenshotPlace[]
  name: string
}>

export type ScreenshotOptions = Readonly<{
  /** `{count} 处`: how many places a bubble holds, drawn beside its name. */
  formatPlaceCount: (count: number) => string
  /** What to draw once the map is framed: places, and bubbles of them, as it shows them. */
  getPlaces: (map: MapboxMap) => Promise<readonly ScreenshotPlace[]>
  /** Drawn centred along the bottom of the map. */
  legend?: Element | null | undefined
  /** Every place, to frame the map on. */
  places: readonly ScreenshotPlace[]
  site: string
  /** Each a phrase of the header's second line, wrapped between phrases. */
  stats: readonly string[]
  theme: Readonly<{ background: string; muted: string; text: string }>
  title: string
}>

type Label = {
  count: string
  /** The place's name as the map shows it (see getShortName). */
  name: string
  /** Set a little away from its dot, joined to it by a line. */
  leader?: boolean
  nameWidth: number
  point: PlacePoint
  rect: Rect
}

type PlacePoint = ScreenshotPlace & {
  x: number
  y: number
  /** For a bubble, where its places are on the map. */
  spots?: PlacePoint[]
}

/** A view of the map copied off its canvas, with where each place is in it. */
type Shot = { image: HTMLCanvasElement; origin: DOMRect; points: PlacePoint[] }

/** Bubbles to magnify together: the area their places take on the map, and a shot zoomed in on them. */
type Zoomed = { area: Rect; places: readonly ScreenshotPlace[]; shot: Shot }

export async function captureMap(map: MapboxMap, options: ScreenshotOptions) {
  const camera = {
    bearing: map.getBearing(),
    center: map.getCenter(),
    padding: map.getPadding(),
    pitch: map.getPitch(),
    zoom: map.getZoom(),
  }
  const container = map.getContainer()
  const containerStyle = container.style.cssText
  const showLabels = hideBasemapLabels(map, KEPT_BASEMAP_LABELS)

  try {
    container.style.cssText += `;inset:auto;left:0;top:0;width:${SHOT_SIZE.width}px;height:${SHOT_SIZE.height}px`
    map.resize()
    frame(map, options.places)
    await waitForIdle(map)

    const overview = await shoot(map, await options.getPlaces(map))

    // Where each bubble's places are on the map, before it moves.
    for (const point of overview.points) {
      if (point.members) {
        point.spots = projectPlaces(
          map,
          point.members,
          overview.origin.width,
          overview.origin.height,
        )
      }
    }

    // The bubbles are magnified, near ones together.
    const zoomed: Zoomed[] = []

    for (const { area, places } of groupBubbles(overview.points).slice(0, MAX_INSETS)) {
      frame(map, places, INSET_PADDING, INSET_MAX_ZOOM)
      await waitForIdle(map)
      zoomed.push({ area, places, shot: await shoot(map, options.places) })
    }

    return await render(options, overview, zoomed)
  } finally {
    showLabels()

    container.style.cssText = containerStyle
    map.resize()
    map.jumpTo(camera)
  }
}

function frame(
  map: MapboxMap,
  places: readonly ScreenshotPlace[],
  padding: number | typeof FRAME_PADDING = FRAME_PADDING,
  maxZoom = FRAME_MAX_ZOOM,
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

/** A place's spot on the earth, to tell it again in another shot. */
function getSpot(place: Pick<ScreenshotPlace, 'latitude' | 'longitude'>) {
  return `${place.longitude},${place.latitude}`
}

/**
 * The bubbles, gathered for magnifiers: each joins a group
 * whose area it comes near while the two together hold few enough places; biggest
 * groups first.
 */
function groupBubbles(points: readonly PlacePoint[]) {
  const groups: { area: Rect; places: ScreenshotPlace[] }[] = []
  const bubbles = points
    .filter((point) => point.members)
    .sort((first, second) => second.members!.length - first.members!.length)

  for (const bubble of bubbles) {
    const area = padRect(boundsOf([getDot(bubble), ...(bubble.spots ?? []).map(getDot)]), 6)
    const near = groups.find(
      (group) =>
        group.places.length + bubble.members!.length <= INSET_MAX_PLACES &&
        overlap(padRect(group.area, INSET_GROUP_GAP), area) > 0,
    )

    if (near) {
      near.area = boundsOf([near.area, area])
      near.places.push(...bubble.members!)
    } else {
      groups.push({ area, places: [...bubble.members!] })
    }
  }

  return groups.sort((first, second) => second.places.length - first.places.length)
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

async function render(options: ScreenshotOptions, overview: Shot, zoomed: readonly Zoomed[]) {
  const { image: mapImage, origin, points } = overview
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

  for (const { area, places, shot } of zoomed) {
    const inset = layOutInset(context, font, shot, places, area, {
      avoid: [
        ...zoomed.map((other) => other.area),
        ...insets.map((other) => other.box),
        ...points.map(getDot),
      ],
      bounds: {
        ...band,
        ...getSides(points, [], minWidth, width),
        bottom: legendStrip ? legendStrip.top - INSET_MARGIN : band.bottom,
      },
      formatPlaceCount: options.formatPlaceCount,
      mapHeight: band.bottom - band.top,
    })

    if (inset) {
      areas.push(area)
      insets.push(inset)
    }
  }

  const labels = placeLabels(context, points, points, font, {
    blocked: [...(legendStrip ? [legendStrip] : []), ...insets.map((inset) => inset.box)],
    bounds: { bottom: band.bottom - 4, left: 4, right: width - 4, top: band.top + 4 },
    formatPlaceCount: options.formatPlaceCount,
  })
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
  const height = headerHeight + mapHeight + FOOTER_HEIGHT

  output.width = Math.round(cropWidth * scale)
  output.height = Math.round(height * scale)
  context.setTransform(scale, 0, 0, scale, 0, 0)
  context.fillStyle = options.theme.background
  context.fillRect(0, 0, cropWidth, height)

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
 * Sizes the magnifier to its places and sets it in the free spot of `bounds` nearest
 * them, as large as fits, then places its dots and names. None if it wouldn't show
 * them much larger than the map does.
 */
function layOutInset(
  context: CanvasRenderingContext2D,
  font: string,
  shot: Shot,
  places: readonly ScreenshotPlace[],
  area: Rect,
  view: {
    avoid: readonly Rect[]
    bounds: Rect
    formatPlaceCount: (count: number) => string
    mapHeight: number
  },
): Inset | undefined {
  const placeSpots = new Set(places.map(getSpot))
  const inShot = shot.points.filter((point) => placeSpots.has(getSpot(point)))
  const placesBox = boundsOf(inShot.map(getDot))
  const source = fitAspect(padRect(placesBox, INSET_PADDING), shot.origin)
  const sourceWidth = source.right - source.left
  const sourceHeight = source.bottom - source.top
  const boundsWidth = view.bounds.right - view.bounds.left
  const largest = Math.min(
    Math.min(boundsWidth * INSET_MAX_SHARE.width, INSET_MAX_WIDTH) / sourceWidth,
    (view.mapHeight * INSET_MAX_SHARE.height) / sourceHeight,
  )
  const { bottom, left, right, top } = view.bounds
  const centre = { x: (area.left + area.right) / 2, y: (area.top + area.bottom) / 2 }
  const covered = (rect: Rect) =>
    view.avoid.reduce((total, other) => total + overlap(other, rect), 0)
  const spotsAt = (fit: number) => {
    const width = sourceWidth * fit
    const height = sourceHeight * fit
    const spots: { distance: number; fit: number; rect: Rect }[] = []

    for (let y = top + INSET_MARGIN; y + height <= bottom; y += INSET_STEP) {
      for (let x = left + INSET_MARGIN; x + width <= right - INSET_MARGIN; x += INSET_STEP) {
        const rect = { bottom: y + height, left: x, right: x + width, top: y }

        spots.push({
          distance: Math.hypot(x + width / 2 - centre.x, y + height / 2 - centre.y),
          fit,
          rect,
        })
      }
    }

    return spots
  }
  // The largest size with a spot over no dot, area or other magnifier, in the spot of
  // those nearest its places; failing that, the smallest size where it covers least.
  const sizes = [1, 0.8, 0.64, 0.5].map((step) => largest * step)
  const free = sizes
    .map((fit) =>
      spotsAt(fit)
        .filter(({ rect }) => covered(rect) === 0)
        .sort((first, second) => first.distance - second.distance),
    )
    .find((spots) => spots.length > 0)?.[0]
  const fallback = spotsAt(sizes.at(-1)!).reduce<
    { distance: number; fit: number; rect: Rect } | undefined
  >((best, next) => (!best || covered(next.rect) < covered(best.rect) ? next : best), undefined)
  const chosen = free ?? fallback

  if (!chosen) {
    return undefined
  }

  const { fit, rect: box } = chosen

  // Not much bigger than on the map: the places is better left nudged apart there.
  if (((placesBox.right - placesBox.left) * fit) / (area.right - area.left) < INSET_MIN_GAIN) {
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

  return {
    box,
    image: shot.image,
    labels: placeLabels(context, points, points, font, {
      blocked: [],
      bounds: padRect(box, -4),
      formatPlaceCount: view.formatPlaceCount,
    }),
    points,
    scale: shot.image.width / shot.origin.width,
    source,
  }
}

/** The magnifier in its card, and a line to the area it shows on the map. */
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

/** A place's dot grows with its Moments; a bubble is wide enough for its number. */
function radius(point: Pick<PlacePoint, 'count' | 'members'>) {
  return point.members
    ? 7 + String(point.count).length * 2
    : Math.min(3 + Math.sqrt(point.count) * 1.4, 10)
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
  view: { blocked: readonly Rect[]; bounds: Rect; formatPlaceCount: (count: number) => string },
) {
  const dots = points.map(getDot)
  const { bounds } = view
  const labels: Label[] = []

  for (const point of named) {
    // A bubble's Moments are in it already; its label says how many places it holds.
    // A single Moment goes without a number.
    const count = point.members
      ? view.formatPlaceCount(point.members.length)
      : point.count > 1
        ? String(point.count)
        : ''

    context.font = `500 12px ${font}`
    const name = getShortName(point.name)
    const nameWidth = context.measureText(name).width
    context.font = COUNT_FONT
    const countWidth = context.measureText(count).width
    const width = count ? nameWidth + 4 + countWidth : nameWidth
    const spot = getLabelRects(point, radius(point), width).find(
      ({ rect: candidate }) =>
        candidate.left >= bounds.left &&
        candidate.right <= bounds.right &&
        candidate.top >= bounds.top &&
        candidate.bottom <= bounds.bottom &&
        !labels.some((label) => overlap(label.rect, candidate) > 0) &&
        !dots.some((dot, index) => points[index] !== point && overlap(dot, candidate) > 0) &&
        !view.blocked.some((rect) => overlap(rect, candidate) > 0),
    )

    if (spot) {
      labels.push({ count, leader: spot.leader, name, nameWidth, point, rect: spot.rect })
    }
  }

  return labels
}

/**
 * A name without the branch in brackets at its end, as in 遇约烤鸭（中粮店）: on the
 * map the place is clear without it, and it takes the room of another name.
 */
function getShortName(name: string) {
  return name.replace(/\s*[（(][^（）()]*[）)]$/, '') || name
}

/** Each place as a dot sized by its Moments, then the names placed for them. */
function drawPlaces(
  context: CanvasRenderingContext2D,
  points: readonly PlacePoint[],
  labels: readonly Label[],
  theme: ScreenshotOptions['theme'],
  font: string,
) {
  for (const point of points) {
    drawDot(context, point)
  }

  for (const { count, leader, name, point, nameWidth, rect } of labels) {
    const middle = (rect.top + rect.bottom) / 2

    if (leader) {
      // From the dot's edge to the nearest point of the name.
      const toX = Math.max(rect.left, Math.min(rect.right, point.x))
      const toY = Math.max(rect.top, Math.min(rect.bottom, point.y))
      const length = Math.hypot(toX - point.x, toY - point.y)
      const start = radius(point) / length

      context.save()
      context.strokeStyle = theme.muted
      context.lineWidth = 1
      context.beginPath()
      context.moveTo(point.x + (toX - point.x) * start, point.y + (toY - point.y) * start)
      context.lineTo(toX, toY)
      context.stroke()
      context.restore()
    }

    context.textBaseline = 'middle'
    context.textAlign = 'left'
    context.lineJoin = 'round'
    context.lineWidth = 3
    context.strokeStyle = theme.background
    context.font = `500 12px ${font}`
    context.strokeText(name, rect.left, middle)
    context.fillStyle = theme.text
    context.fillText(name, rect.left, middle)

    if (count) {
      context.font = COUNT_FONT
      context.strokeText(count, rect.left + nameWidth + 4, middle)
      context.fillStyle = theme.muted
      context.fillText(count, rect.left + nameWidth + 4, middle)
    }
  }
}

/** A place's dot, or a bubble with its Moments in it, as the map draws them. */
function drawDot(context: CanvasRenderingContext2D, point: PlacePoint) {
  const size = radius(point)

  context.save()
  context.beginPath()
  context.arc(point.x, point.y, size, 0, Math.PI * 2)
  context.shadowColor = 'rgb(0 0 0 / 0.25)'
  context.shadowBlur = 4
  context.fillStyle = point.color
  context.fill()
  context.shadowColor = 'transparent'
  context.lineWidth = point.members ? 2 : 1.5
  context.strokeStyle = 'rgb(255 255 255 / 0.95)'
  context.stroke()

  if (point.members) {
    context.font = `600 10px ui-monospace, monospace`
    context.textAlign = 'center'
    context.textBaseline = 'middle'
    context.fillStyle = '#fff'
    context.fillText(String(point.count), point.x, point.y + 0.5)
  }

  context.restore()
}

/**
 * A label's spots around a dot, best first: right beside it (to its right, left, above
 * and below, then at its corners), then the same a little further out, joined to it by
 * a line.
 */
function getLabelRects({ x, y }: PlacePoint, radius: number, width: number) {
  const around = (offset: number, leader: boolean) => {
    const corner = offset * Math.SQRT1_2
    const box = (left: number, top: number) => ({
      leader,
      rect: { bottom: top + LABEL_HEIGHT, left, right: left + width, top },
    })
    return [
      box(x + offset, y - LABEL_HEIGHT / 2),
      box(x - offset - width, y - LABEL_HEIGHT / 2),
      box(x - width / 2, y - offset - LABEL_HEIGHT),
      box(x - width / 2, y + offset),
      box(x + corner, y - corner - LABEL_HEIGHT),
      box(x + corner, y + corner),
      box(x - corner - width, y - corner - LABEL_HEIGHT),
      box(x - corner - width, y + corner),
    ]
  }

  return [
    ...around(radius + LABEL_GAP, false),
    ...around(radius + LABEL_GAP + LEADER_LENGTH, true),
    ...around(radius + LABEL_GAP + LEADER_LENGTH * 2, true),
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
