/**
 * @license MIT
 * Ported from Base UI drawer/root/useDrawerSnapPoints
 * (@base-ui/react 1.9.0, https://github.com/mui/base-ui), Copyright (c) 2019 Material-UI SAS.
 * Changes Copyright (c) 2026 Stephen Zhou. See LICENSE and THIRD-PARTY-NOTICES.md.
 */

// Where a drawer swiped up or down may rest short of fully open, as Base UI's
// useDrawerSnapPoints has them: a number up to 1 is a share of the viewport's height,
// above 1 pixels, and a string is pixels ('320px') or rems ('20rem'). Each rests the popup
// `offset` pixels short of its full height.

export type SnapPoint = number | string

export type ResolvedSnapPoint = {
  value: SnapPoint
  /** How much of the popup shows, in pixels. */
  height: number
  /** How far the popup is moved out of sight to show only `height` of it. */
  offset: number
}

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max)

export function resolveSnapPointHeight(
  snapPoint: SnapPoint,
  viewportHeight: number,
  rootFontSize: number,
): number | null {
  if (!Number.isFinite(viewportHeight) || viewportHeight <= 0) {
    return null
  }

  if (typeof snapPoint === 'number') {
    if (!Number.isFinite(snapPoint)) {
      return null
    }

    return snapPoint <= 1 ? clamp(snapPoint, 0, 1) * viewportHeight : snapPoint
  }

  const trimmed = snapPoint.trim()
  const value = Number.parseFloat(trimmed)

  if (!Number.isFinite(value)) {
    return null
  }

  if (trimmed.endsWith('rem')) {
    return value * rootFontSize
  }

  return trimmed.endsWith('px') ? value : null
}

/** The snap points as heights and offsets, the ones that come out the same height dropped. */
export function resolveSnapPoints(
  snapPoints: readonly SnapPoint[] | undefined,
  { popupHeight, rootFontSize, viewportHeight }: SnapMetrics,
): ResolvedSnapPoint[] {
  if (!snapPoints?.length || viewportHeight <= 0 || popupHeight <= 0) {
    return []
  }

  const maxHeight = Math.min(popupHeight, viewportHeight)
  const resolved: ResolvedSnapPoint[] = []

  for (const value of snapPoints) {
    const height = resolveSnapPointHeight(value, viewportHeight, rootFontSize)

    if (height !== null) {
      const clamped = clamp(height, 0, maxHeight)
      resolved.push({ value, height: clamped, offset: Math.max(0, popupHeight - clamped) })
    }
  }

  // The later of two points a pixel apart wins, as in Base UI.
  const kept: ResolvedSnapPoint[] = []

  for (let index = resolved.length - 1; index >= 0; index -= 1) {
    const point = resolved[index]!

    if (!kept.some((other) => Math.abs(other.height - point.height) <= 1)) {
      kept.unshift(point)
    }
  }

  return kept
}

export type SnapMetrics = {
  popupHeight: number
  rootFontSize: number
  viewportHeight: number
}

/** The resolved point for `active`: the same value, or else the one nearest its height. */
export function findActiveSnapPoint(
  active: SnapPoint | null,
  resolved: readonly ResolvedSnapPoint[],
  metrics: SnapMetrics,
): ResolvedSnapPoint | undefined {
  if (active === null) {
    return undefined
  }

  const exact = resolved.find((point) => Object.is(point.value, active))

  if (exact) {
    return exact
  }

  const height = resolveSnapPointHeight(active, metrics.viewportHeight, metrics.rootFontSize)

  if (height === null) {
    return undefined
  }

  const clamped = clamp(height, 0, Math.min(metrics.popupHeight, metrics.viewportHeight))

  return resolved[
    closestIndex(
      resolved.map((point) => point.height),
      clamped,
    )
  ]
}

/** The index of the value nearest `target`, or -1 for none. */
export function closestIndex(values: readonly number[], target: number) {
  let closest = -1
  let distance = Infinity

  for (const [index, value] of values.entries()) {
    if (Math.abs(value - target) < distance) {
      distance = Math.abs(value - target)
      closest = index
    }
  }

  return closest
}

/**
 * The movement down for a drag of `movement` from a point `baseOffset` short of open,
 * resisting (by its square root) once the drag pulls past fully open.
 */
export function getSnapPointSwipeMovement(baseOffset: number, movement: number) {
  const next = baseOffset + movement

  return next >= 0 ? movement : -Math.sqrt(-next) - baseOffset
}
