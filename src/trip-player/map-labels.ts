import type { Map as MapboxMap } from 'mapbox-gl'
import { createElement } from './dom'
import type { MapLabel } from './types'

// Place names over the map during playback. Mapbox popups snap to whole pixels, so
// while the camera glides they step a pixel at a time; these are placed with
// sub-pixel transforms in the same frame the map draws, so they move with it exactly.

// How far above its place a label sits, clear of the stop's dot.
const OFFSET = 12

export function createMapLabels(getMap: () => MapboxMap | undefined) {
  let layer: HTMLElement | undefined
  let labels: { coordinates: [number, number]; element: HTMLElement }[] = []
  let watched: MapboxMap | undefined
  // Labels open above their place, to the right of it when the card sits on the left.
  let alignLeft = false

  function place() {
    const map = getMap()

    if (!map) {
      return
    }

    for (const { coordinates, element } of labels) {
      const { x, y } = map.project(coordinates)
      element.style.transform = `translate3d(${x}px, ${y - OFFSET}px, 0) translate(${alignLeft ? '0' : '-50%'}, -100%)`
    }
  }

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

    alignLeft = options.alignLeft
    labels = next.map(({ coordinates, eyebrow, name }) => {
      const element = createElement('div', 'calendar-map-trip-label', '')

      if (eyebrow) {
        element.append(createElement('p', 'text-[0.6875rem] text-tx-3 tabular-nums', eyebrow))
      }

      element.append(createElement('p', 'text-sm font-medium', name))
      layer!.append(element)
      return { coordinates, element }
    })
    place()
  }

  function clear() {
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
