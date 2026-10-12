import mapboxgl, { type Map as MapboxMap } from 'mapbox-gl'
import { overlap, type Rect } from './trip-player/label-placement'

// A picture of the map made for sharing rather than a copy of the screen: the view
// framed on every place, the basemap's lesser names put away, and each place drawn
// with its name and count, under a header with the totals. The view is put back after.
//
// A larger picture draws the map on a canvas a few times the screen's size: the
// places spread apart, so more of them get a name, while the header, legend and
// footer grow with it to keep the same look.

const ATTRIBUTION = '© Mapbox © OpenStreetMap'
// The strip under the map for the site and the attribution, in CSS pixels.
const FOOTER_HEIGHT = 32
// Room around the framed places; the bottom also clears the legend drawn over the map.
const FRAME_PADDING = { bottom: 120, left: 32, right: 32, top: 32 }
const FRAME_MAX_ZOOM = 5
// Places closer than this on screen, in pixels, would sit on one another, so they
// share one dot and one label; the map's 地点 setting does the rest of the gathering.
const MERGE_DISTANCE = 5
// The longest side, in device pixels, the map's canvas is drawn at; well inside what
// browsers allow a WebGL canvas.
const MAX_CANVAS_SIDE = 8192
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

export type ScreenshotPlace = Readonly<{
  color: string
  count: number
  latestYear: number
  latitude: number
  longitude: number
  name: string
}>

export type ScreenshotOptions = Readonly<{
  /**
   * The places to draw, asked for once the map is framed: gathered as the map
   * gathers them there, so the picture shows what the 地点 setting does.
   */
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
  /** How many times the screen's size to draw the map at, within MAX_CANVAS_SIDE. */
  size: number
}>

type Label = { count: string; group: Group; nameWidth: number; rect: Rect }

type Group = {
  color: string
  count: number
  latestYear: number
  name: string
  x: number
  y: number
}

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
  const { height, width } = container.getBoundingClientRect()
  const pixels = (window.devicePixelRatio || 1) * Math.max(width, height)
  const factor = Math.max(1, Math.min(options.size, MAX_CANVAS_SIDE / pixels))
  const hiddenLabels = hideBasemapLabels(map)

  try {
    if (factor > 1) {
      container.style.cssText += `;inset:auto;left:0;top:0;width:${width * factor}px;height:${height * factor}px`
      map.resize()
    }

    frame(map, options.places, factor)
    await waitForIdle(map)

    return await render(map, options, await options.getPlaces(map), factor)
  } finally {
    for (const id of hiddenLabels) {
      map.setLayoutProperty(id, 'visibility', 'visible')
    }

    if (factor > 1) {
      container.style.cssText = containerStyle
      map.resize()
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

function frame(map: MapboxMap, places: readonly ScreenshotPlace[], factor: number) {
  if (places.length === 0) {
    return
  }

  const bounds = new mapboxgl.LngLatBounds()

  for (const place of places) {
    bounds.extend([place.longitude, place.latitude])
  }

  // The drawer's room, if one is open, would push the places aside.
  map.jumpTo({ padding: { bottom: 0, left: 0, right: 0, top: 0 } })

  const camera = map.cameraForBounds(bounds, {
    maxZoom: FRAME_MAX_ZOOM + Math.log2(factor),
    padding: FRAME_PADDING,
  })

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
  map: MapboxMap,
  options: ScreenshotOptions,
  places: readonly ScreenshotPlace[],
  factor: number,
) {
  const mapImage = await copyCanvas(map)
  const origin = map.getCanvas().getBoundingClientRect()
  const scale = mapImage.width / origin.width
  const font = getComputedStyle(document.body).fontFamily
  const output = document.createElement('canvas')
  const context = output.getContext('2d')!
  const width = origin.width
  // The header, legend and footer grow with the picture; the places don't.
  const ui = factor
  const legendBox = options.legend?.getBoundingClientRect()
  const legendSize =
    legendBox && legendBox.width > 0
      ? { height: legendBox.height * ui, width: legendBox.width * ui }
      : undefined

  const groups = groupPlaces(map, places, width, origin.height)
  const band = getBand(groups, legendSize?.height ?? 0, origin.height)
  const legendTop = band.bottom - LEGEND_GAP * ui - (legendSize?.height ?? 0)
  const labels = placeLabels(context, groups, font, {
    bounds: { bottom: band.bottom - 4, left: 4, right: width - 4, top: band.top + 4 },
    // The whole strip the legend goes in, as it's centred once the sides are cut.
    legend: legendSize && {
      bottom: legendTop + legendSize.height,
      left: 0,
      right: width,
      top: legendTop,
    },
  })
  const crop = {
    ...band,
    ...getSides(
      groups,
      labels,
      Math.max(MIN_CROP_WIDTH * ui, (legendSize?.width ?? 0) + CROP_MARGIN * 2),
      width,
    ),
  }
  const cropWidth = crop.right - crop.left
  const mapHeight = crop.bottom - crop.top
  const footerHeight = FOOTER_HEIGHT * ui

  // The header's lines are measured before the canvas has its size.
  context.font = `${12 * ui}px ${font}`
  const statLines = wrapPhrases(context, options.stats, cropWidth - 32 * ui)
  const headerHeight = (16 + 22 + 6 + statLines.length * 18 + 12) * ui

  output.width = Math.round(cropWidth * scale)
  output.height = Math.round((headerHeight + mapHeight + footerHeight) * scale)
  context.setTransform(scale, 0, 0, scale, 0, 0)
  context.fillStyle = options.theme.background
  context.fillRect(0, 0, cropWidth, headerHeight + mapHeight + footerHeight)

  drawHeader(context, options, statLines, font, ui)

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
  drawPlaces(context, groups, labels, options.theme, font)

  if (options.legend && legendBox && legendSize) {
    context.translate((crop.left + crop.right - legendSize.width) / 2, legendTop)
    context.scale(ui, ui)
    context.translate(-legendBox.left, -legendBox.top)
    drawElement(context, options.legend)
  }

  context.restore()
  drawFooter(context, options, cropWidth, headerHeight + mapHeight, ui)

  return new Promise<Blob>((resolve, reject) =>
    output.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Empty capture'))),
      'image/png',
    ),
  )
}

function drawHeader(
  context: CanvasRenderingContext2D,
  options: ScreenshotOptions,
  statLines: readonly string[],
  font: string,
  ui: number,
) {
  context.textAlign = 'left'
  context.textBaseline = 'top'
  context.fillStyle = options.theme.text
  context.font = `600 ${18 * ui}px ${font}`
  context.fillText(options.title, 16 * ui, 16 * ui)
  context.fillStyle = options.theme.muted
  context.font = `${12 * ui}px ${font}`

  for (const [index, line] of statLines.entries()) {
    context.fillText(line, 16 * ui, (16 + 22 + 6 + index * 18) * ui)
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

/**
 * The places on screen, any sitting on one another merged into the one with the
 * most Moments, busiest first.
 */
function groupPlaces(
  map: MapboxMap,
  places: readonly ScreenshotPlace[],
  width: number,
  height: number,
) {
  const groups: Group[] = []
  const sorted = places
    .map((place) => {
      const { x, y } = map.project([place.longitude, place.latitude])
      return { ...place, x, y }
    })
    .filter(({ x, y }) => x >= 0 && x <= width && y >= 0 && y <= height)
    .sort((first, second) => second.count - first.count || first.name.length - second.name.length)

  for (const place of sorted) {
    const near = groups.find(
      (group) => Math.hypot(group.x - place.x, group.y - place.y) < MERGE_DISTANCE,
    )

    if (!near) {
      groups.push({ ...place })
      continue
    }

    near.count += place.count

    if (place.latestYear > near.latestYear) {
      near.color = place.color
      near.latestYear = place.latestYear
    }
  }

  return groups
}

function radius(group: Group) {
  return Math.min(4 + Math.sqrt(group.count) * 1.2, 10)
}

/**
 * The band of the map the places are in, from just above the highest to the legend
 * below the lowest: a phone held upright frames them by width, leaving sky and sea.
 */
function getBand(groups: readonly Group[], legendHeight: number, height: number) {
  if (groups.length === 0) {
    return { bottom: height, top: 0 }
  }

  const legendRoom = legendHeight > 0 ? legendHeight + LEGEND_GAP * 2 : 0
  const top =
    Math.min(...groups.map((group) => group.y - radius(group))) - LABEL_HEIGHT - CROP_MARGIN
  const bottom =
    Math.max(...groups.map((group) => group.y + radius(group))) +
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
  groups: readonly Group[],
  labels: readonly Label[],
  minWidth: number,
  width: number,
) {
  const boxes = [...groups.map(getDot), ...labels.map((label) => label.rect)]

  if (boxes.length === 0) {
    return { left: 0, right: width }
  }

  const left = Math.min(...boxes.map((box) => box.left)) - CROP_MARGIN
  const right = Math.max(...boxes.map((box) => box.right)) + CROP_MARGIN
  const least = Math.min(width, minWidth)
  const extra = Math.max(least - (right - left), 0) / 2
  const shift = Math.max(0 - (left - extra), 0) - Math.max(right + extra - width, 0)

  return {
    left: Math.max(left - extra + shift, 0),
    right: Math.min(right + extra + shift, width),
  }
}

function getDot(group: Group): Rect {
  const size = radius(group)
  return {
    bottom: group.y + size,
    left: group.x - size,
    right: group.x + size,
    top: group.y - size,
  }
}

/**
 * Names where they fit, the busiest places first: right beside the place's own dot,
 * so it can't be read as a neighbour's, and over no other dot, name or the legend.
 * A place with no such spot goes unnamed.
 */
function placeLabels(
  context: CanvasRenderingContext2D,
  groups: readonly Group[],
  font: string,
  view: { bounds: Rect; legend: Rect | null | undefined },
) {
  const dots = groups.map(getDot)
  const { bounds } = view
  const labels: Label[] = []

  for (const group of groups) {
    const count = String(group.count)

    context.font = `500 12px ${font}`
    const nameWidth = context.measureText(group.name).width
    context.font = COUNT_FONT
    const countWidth = context.measureText(count).width
    const rect = getLabelRects(group, radius(group), nameWidth + 4 + countWidth).find(
      (candidate) =>
        candidate.left >= bounds.left &&
        candidate.right <= bounds.right &&
        candidate.top >= bounds.top &&
        candidate.bottom <= bounds.bottom &&
        !labels.some((label) => overlap(label.rect, candidate) > 0) &&
        !dots.some((dot, index) => groups[index] !== group && overlap(dot, candidate) > 0) &&
        !(view.legend && overlap(view.legend, candidate) > 0),
    )

    if (rect) {
      labels.push({ count, group, nameWidth, rect })
    }
  }

  return labels
}

/** Each place as a dot sized by its Moments, then the names placed for them. */
function drawPlaces(
  context: CanvasRenderingContext2D,
  groups: readonly Group[],
  labels: readonly Label[],
  theme: ScreenshotOptions['theme'],
  font: string,
) {
  context.save()
  context.shadowColor = 'rgb(0 0 0 / 0.25)'
  context.shadowBlur = 4

  for (const group of groups) {
    context.beginPath()
    context.arc(group.x, group.y, radius(group), 0, Math.PI * 2)
    context.fillStyle = group.color
    context.fill()
  }

  context.shadowColor = 'transparent'
  context.lineWidth = 1.5
  context.strokeStyle = 'rgb(255 255 255 / 0.95)'

  for (const group of groups) {
    context.beginPath()
    context.arc(group.x, group.y, radius(group), 0, Math.PI * 2)
    context.stroke()
  }

  context.restore()

  for (const { count, group, nameWidth, rect } of labels) {
    const middle = (rect.top + rect.bottom) / 2

    context.textBaseline = 'middle'
    context.textAlign = 'left'
    context.lineJoin = 'round'
    context.lineWidth = 3
    context.strokeStyle = theme.background
    context.font = `500 12px ${font}`
    context.strokeText(group.name, rect.left, middle)
    context.fillStyle = theme.text
    context.fillText(group.name, rect.left, middle)
    context.font = COUNT_FONT
    context.strokeText(count, rect.left + nameWidth + 4, middle)
    context.fillStyle = theme.muted
    context.fillText(count, rect.left + nameWidth + 4, middle)
  }
}

/** A label's spots around a dot, best first: to its right, left, above and below. */
function getLabelRects({ x, y }: Group, radius: number, width: number): Rect[] {
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
  ui: number,
) {
  const margin = 12 * ui
  const middle = top + (FOOTER_HEIGHT * ui) / 2

  context.font = `500 ${12 * ui}px ui-monospace, monospace`
  context.textBaseline = 'middle'
  context.fillStyle = options.theme.muted
  context.textAlign = 'left'
  context.fillText(options.site, margin, middle)
  context.textAlign = 'right'
  context.font = `${11 * ui}px system-ui, sans-serif`
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
