// Where a place's label goes around its point: on screen, clear of the cards over
// the map, and off the markers and the other labels. DOM-free, in screen pixels.

export type Rect = { bottom: number; left: number; right: number; top: number }
export type Size = { height: number; width: number }
export type Point = { x: number; y: number }

export type Placement =
  | 'bottom'
  | 'bottom-left'
  | 'bottom-right'
  | 'left'
  | 'right'
  | 'top'
  | 'top-left'
  | 'top-right'

// Room between the point and the label, clear of the stop's dot and its ring.
const GAP = 14
// How far a corner label reaches back over its point's side.
const CORNER = 12
// A label stays where it is unless somewhere else is clearly better, so it doesn't
// flick between two nearly equal spots as the map moves.
const STAY_MARGIN = 400

/** The label's box when put at `placement` around `point`. */
export function placeLabel(placement: Placement, { x, y }: Point, { height, width }: Size): Rect {
  const above = y - GAP - height
  const below = y + GAP
  const left = {
    bottom: x - width / 2,
    'bottom-left': x - width + CORNER,
    'bottom-right': x - CORNER,
    left: x - GAP - width,
    right: x + GAP,
    top: x - width / 2,
    'top-left': x - width + CORNER,
    'top-right': x - CORNER,
  }[placement]
  const top =
    placement === 'left' || placement === 'right'
      ? y - height / 2
      : placement.startsWith('top')
        ? above
        : below

  return { bottom: top + height, left, right: left + width, top }
}

/**
 * Picks the least crowded placement: anything outside `bounds` or under `blocked`
 * (cards over the map) counts heavily, covering a marker or another label less, and
 * earlier entries of `preferred` win ties. `current` is kept unless clearly beaten.
 */
export function chooseLabelPlacement({
  avoid,
  blocked,
  bounds,
  current,
  point,
  preferred,
  size,
}: {
  /** Markers and labels already placed, best not covered. */
  avoid: readonly Rect[]
  blocked: readonly Rect[]
  bounds: Rect
  current?: Placement | undefined
  point: Point
  preferred: readonly Placement[]
  size: Size
}) {
  const score = (placement: Placement) => {
    const rect = slideInside(placeLabel(placement, point, size), placement, bounds)
    const area = size.width * size.height
    const visible = overlap(rect, bounds)
    const covered = blocked.reduce((total, other) => total + overlap(rect, other), 0)
    const crowded = avoid.reduce((total, other) => total + overlap(rect, other), 0)

    return {
      placement,
      rect,
      score: (area - visible + covered) * 10 + crowded * 3 + preferred.indexOf(placement) * 20,
    }
  }
  const options = preferred.map(score)
  const best = options.reduce((first, second) => (second.score < first.score ? second : first))
  const kept = current && options.find((option) => option.placement === current)

  return kept && kept.score <= best.score + STAY_MARGIN ? kept : best
}

/**
 * Slides a label along the side of its point it is on until it's within `bounds`:
 * one above or below moves sideways, one beside moves up or down, so it never comes
 * over the point itself.
 */
function slideInside(rect: Rect, placement: Placement, bounds: Rect): Rect {
  const sideways = placement !== 'left' && placement !== 'right'
  const shift = (start: number, end: number, min: number, max: number) =>
    end - start > max - min ? 0 : Math.max(min - start, Math.min(0, max - end))

  if (sideways) {
    const dx = shift(rect.left, rect.right, bounds.left, bounds.right)
    return { ...rect, left: rect.left + dx, right: rect.right + dx }
  }

  const dy = shift(rect.top, rect.bottom, bounds.top, bounds.bottom)
  return { ...rect, bottom: rect.bottom + dy, top: rect.top + dy }
}

export function overlap(first: Rect, second: Rect) {
  const width = Math.min(first.right, second.right) - Math.max(first.left, second.left)
  const height = Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top)
  return width > 0 && height > 0 ? width * height : 0
}

export function contains(rect: Rect, { x, y }: Point) {
  return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
}
