import type { Map as MapboxMap } from 'mapbox-gl'
import type { Coordinates } from '../geo.ts'
import { createElement } from './dom.ts'
import {
  chooseLabelPlacement,
  contains,
  type Placement,
  type Point,
  type Rect,
  type Size,
} from './label-placement.ts'
import type { MapLabel } from './types.ts'

// Place names over the map during playback. Mapbox popups snap to whole pixels, so
// while the camera glides they step a pixel at a time; these are placed with
// sub-pixel transforms in the same frame the map draws, so they move with it exactly.
// Each frame a label takes the clearest spot around its place, kept on screen, out
// from under the cards and off the markers, and glides there when it has to move.

// Keeps labels this far inside the map's edges.
const EDGE = 8
// Seconds for a label to glide most of the way to a new spot.
const GLIDE = 0.14

/** A dot or icon on the map that labels shouldn't cover, by its radius in pixels. */
type LabelMarker = { coordinates: Coordinates; radius: number }

type PlacedLabel = {
  coordinates: [number, number]
  element: HTMLElement
  /** Where the box sits relative to its point, easing toward the chosen spot. */
  offset?: Point
  placement?: Placement
  size?: Size
  visible: boolean
}

export function createMapLabels({
  getLayout,
  getMap,
  getMarkers,
}: {
  /**
   * The map's box, the cards and controls over it that labels keep out from under, and
   * other boxes they keep clear of, in viewport pixels.
   */
  getLayout: () => { avoid: DOMRect[]; blocked: DOMRect[]; container: DOMRect | undefined }
  getMap: () => MapboxMap | undefined
  getMarkers: () => LabelMarker[]
}) {
  let layer: HTMLElement | undefined
  let labels: PlacedLabel[] = []
  let watched: MapboxMap | undefined
  let preferred: Placement[] = []
  let lastTime: number | undefined
  let frame: number | undefined

  function place() {
    const map = getMap()
    const now = performance.now()
    const seconds = lastTime === undefined ? 1 : Math.min((now - lastTime) / 1000, 0.1)

    lastTime = now

    if (!map || labels.length === 0) {
      return
    }

    const layout = getLayout()
    const container = layout.container ?? map.getContainer().getBoundingClientRect()
    const toLocal = (rect: DOMRect): Rect => ({
      bottom: rect.bottom - container.top,
      left: rect.left - container.left,
      right: rect.right - container.left,
      top: rect.top - container.top,
    })
    const bounds = {
      bottom: container.height - EDGE,
      left: EDGE,
      right: container.width - EDGE,
      top: EDGE,
    }
    const blocked = layout.blocked.map(toLocal)
    const avoid: Rect[] = [
      ...getMarkers().map(({ coordinates, radius }) => {
        const { x, y } = map.project([coordinates[0], coordinates[1]])
        return { bottom: y + radius, left: x - radius, right: x + radius, top: y - radius }
      }),
      ...layout.avoid.map(toLocal),
    ]
    const glide = 1 - Math.exp(-seconds / GLIDE)
    let gliding = false

    for (const label of labels) {
      const point = map.project(label.coordinates)
      const size = (label.size ??= {
        height: label.element.offsetHeight,
        width: label.element.offsetWidth,
      })
      // A place off screen or under a card would only leave its label stranded.
      const visible = contains(bounds, point) && !blocked.some((rect) => contains(rect, point))
      const choice = chooseLabelPlacement({
        avoid,
        blocked,
        bounds,
        current: label.placement,
        point,
        preferred,
        size,
      })
      const target = { x: choice.rect.left - point.x, y: choice.rect.top - point.y }
      const offset = label.offset ?? target

      offset.x += (target.x - offset.x) * glide
      offset.y += (target.y - offset.y) * glide
      gliding ||= Math.abs(target.x - offset.x) + Math.abs(target.y - offset.y) > 0.25
      label.offset = offset
      label.placement = choice.placement

      if (visible !== label.visible) {
        label.visible = visible
        label.element.style.opacity = visible ? '' : '0'
      }

      label.element.style.transform = `translate3d(${point.x + offset.x}px, ${point.y + offset.y}px, 0)`
      // Later labels keep off this one.
      avoid.push({
        bottom: point.y + offset.y + size.height,
        left: point.x + offset.x,
        right: point.x + offset.x + size.width,
        top: point.y + offset.y,
      })
    }

    // The map only redraws while it moves; a label still gliding asks for frames itself.
    cancelAnimationFrame(frame ?? 0)
    frame = gliding ? requestAnimationFrame(place) : undefined
  }

  /**
   * Shows labels for places; `alignLeft` prefers opening to the right of the place,
   * for when a card sits on the left of the map.
   */
  function show(next: readonly MapLabel[], options: { alignLeft: boolean }) {
    const map = getMap()

    clear()

    if (!map) {
      return
    }

    if (watched !== map) {
      watched?.off('render', place)
      map.on('render', place)
      watched = map
    }

    if (!layer?.isConnected) {
      layer = createElement('div', 'calendar-map-trip-labels', '')
      layer.setAttribute('aria-hidden', 'true')
      map.getContainer().append(layer)
    }

    preferred = options.alignLeft
      ? ['top-right', 'top', 'right', 'bottom-right', 'top-left', 'bottom', 'left', 'bottom-left']
      : ['top', 'top-right', 'top-left', 'bottom', 'right', 'left', 'bottom-right', 'bottom-left']
    labels = next.map(({ coordinates, name }) => {
      const element = createElement('div', 'calendar-map-trip-label', '')

      element.append(createElement('p', 'calendar-map-trip-label__name', name))
      // Starts hidden and fades in once its place is on screen.
      element.style.opacity = '0'
      layer!.append(element)
      return { coordinates, element, visible: false }
    })
    lastTime = undefined
    place()
  }

  function clear() {
    cancelAnimationFrame(frame ?? 0)
    frame = undefined

    for (const { element } of labels) {
      element.remove()
    }

    labels = []
  }

  function destroy() {
    clear()
    watched?.off('render', place)
    watched = undefined
    layer?.remove()
    layer = undefined
  }

  return { clear, destroy, show }
}
