/**
 * @license MIT
 * Ported from Base UI drawer/root/DrawerRoot, drawer/popup/DrawerPopup,
 * drawer/viewport/DrawerViewport (its release and snap logic) and drawer/backdrop/DrawerBackdrop
 * (@base-ui/react 1.9.0, https://github.com/mui/base-ui), Copyright (c) 2019 Material-UI SAS.
 * Changes Copyright (c) 2026 Stephen Zhou. See LICENSE and THIRD-PARTY-NOTICES.md.
 */

import {
  closestIndex,
  findActiveSnapPoint,
  getSnapPointSwipeMovement,
  resolveSnapPoints,
  type ResolvedSnapPoint,
  type SnapPoint,
} from './snap-points.ts'
import {
  createSwipe,
  getAxis,
  getDisplacement,
  getSwipeSize,
  type SwipeDirection,
  type SwipeRelease,
} from './swipe.ts'

// Base UI's Drawer on plain DOM: one popup (and, if it has them, a backdrop and a viewport
// around it) opened and closed from script, with the state Base UI renders written onto the
// elements as the same data attributes and CSS variables, so a stylesheet written for it,
// as shadcn/ui's, works here (see styles.css):
//
// - `data-open` / `data-closed`, and `data-starting-style` while it comes in and
//   `data-ending-style` while it goes, on the popup and the backdrop;
// - `data-swipe-direction` and `data-swipe-axis`, `data-swiping` while dragged and
//   `data-swipe-dismiss` once swiped away, `data-expanded` at the last snap point, and
//   `data-nested-drawer-open` / `data-nested-drawer-swiping` under a nested drawer;
// - `--drawer-swipe-movement-x/y`, `--drawer-snap-point-offset`, `--drawer-height`,
//   `--drawer-frontmost-height`, `--nested-drawers`, `--drawer-swipe-strength` and, on the
//   backdrop (or on a drawer under a nested one being swiped), `--drawer-swipe-progress`.

export type { SnapPoint, SwipeDirection }

export type OpenChangeReason =
  | 'trigger-press'
  | 'close-press'
  | 'outside-press'
  | 'escape-key'
  | 'close-watcher'
  | 'swipe'
  | 'none'

export type ChangeDetails = {
  reason: OpenChangeReason
  event: Event | undefined
  /** Keeps the drawer as it is. */
  cancel(): void
  readonly isCanceled: boolean
}

type FocusTarget = HTMLElement | null | false | (() => HTMLElement | null | false)

export type DrawerOptions = {
  popup: HTMLElement
  /** Behind the popup, fading as it's swiped away. */
  backdrop?: HTMLElement | null | undefined
  /** Around the popup, where its drags are heard; the popup itself if there's none. */
  viewport?: HTMLElement | null | undefined
  /** Buttons that open it. */
  triggers?: Iterable<HTMLElement> | undefined
  /** The way it's swiped closed. */
  swipeDirection?: SwipeDirection | undefined
  /** Where a drawer swiped up or down rests short of open (see snap-points.ts). */
  snapPoints?: readonly SnapPoint[] | undefined
  /** The one it opens at, the first by default. */
  defaultSnapPoint?: SnapPoint | null | undefined
  /** A fast swipe goes to the next point only, never past it. */
  snapToSequentialPoints?: boolean | undefined
  /**
   * Whether the page behind is out of reach while it's open: shown with `showModal()`
   * when the popup is a `<dialog>`, and the page held from scrolling. On by default.
   */
  modal?: boolean | undefined
  /** Open from the start, as the page rendered it, without coming in. */
  defaultOpen?: boolean | undefined
  /** Keeps a press outside from closing it. */
  disablePointerDismissal?: boolean | undefined
  /** Keeps Escape (and Android's back gesture) from closing it. */
  disableEscapeDismissal?: boolean | undefined
  /**
   * The drawer it opens over, which steps back behind it. Asked each time it opens, so a
   * drawer can open over whichever is open then.
   */
  parent?: Drawer | (() => Drawer | null | undefined) | null | undefined
  /** Focused as it opens: the popup by default; `false` leaves focus where it is. */
  initialFocus?: FocusTarget | undefined
  /** Focused as it closes: where focus was before it opened by default. */
  finalFocus?: FocusTarget | undefined
  /** Before it opens or closes; `details.cancel()` keeps it as it is. */
  onOpenChange?: ((open: boolean, details: ChangeDetails) => void) | undefined
  /** Once it has come in, or gone. */
  onOpenChangeComplete?: ((open: boolean) => void) | undefined
  onSnapPointChange?: ((snapPoint: SnapPoint | null, details: ChangeDetails) => void) | undefined
  signal?: AbortSignal | undefined
}

export type Drawer = {
  readonly popup: HTMLElement
  /** Whether it's open, or opening. */
  readonly isOpen: boolean
  /** Whether it's on the page: open, or still going. */
  readonly isMounted: boolean
  readonly snapPoint: SnapPoint | null
  readonly swipeDirection: SwipeDirection
  open(event?: Event, reason?: OpenChangeReason): boolean
  close(event?: Event, reason?: OpenChangeReason): boolean
  setSnapPoint(snapPoint: SnapPoint | null, event?: Event): void
  setSnapPoints(snapPoints: readonly SnapPoint[] | undefined): void
  setSwipeDirection(direction: SwipeDirection): void
  /** The part of it in sight, in pixels across its swipe axis: less at a snap point. */
  getVisibleSize(): number
  destroy(): void
}

type Internals = {
  onNestedOpenChange(child: Drawer, open: boolean): void
  onNestedPresenceChange(child: Drawer, present: boolean): void
  onNestedFrontmostHeightChange(height: number): void
  onNestedSwipingChange(swiping: boolean): void
  onNestedSwipeProgressChange(progress: number): void
  contains(target: Node): boolean
}

const internals = new WeakMap<Drawer, Internals>()

// What a drawer writes on its elements, taken off again when it's destroyed.
const DRAWER_ATTRIBUTES = new Set([
  'data-drawer-popup',
  'data-drawer-backdrop',
  'data-drawer-viewport',
  'data-open',
  'data-closed',
  'data-starting-style',
  'data-ending-style',
  'data-snap-points',
  'data-swipe-direction',
  'data-swipe-axis',
  'data-swiping',
  'data-swipe-dismiss',
  'data-expanded',
  'data-nested',
  'data-nested-drawer-open',
  'data-nested-drawer-swiping',
])

const MIN_SWIPE_THRESHOLD = 10
const FAST_SWIPE_VELOCITY = 0.5
const SNAP_VELOCITY_THRESHOLD = 0.5
const SNAP_VELOCITY_MULTIPLIER = 300
const MAX_SNAP_VELOCITY = 4
const MIN_SWIPE_RELEASE_VELOCITY = 0.2
const MAX_SWIPE_RELEASE_VELOCITY = 4
const MIN_SWIPE_RELEASE_DURATION_MS = 80
const MAX_SWIPE_RELEASE_DURATION_MS = 360
const MIN_SWIPE_RELEASE_SCALAR = 0.1
const MAX_SWIPE_RELEASE_SCALAR = 1

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max)

let propertiesRegistered = false

/**
 * Keeps the variables a drag changes every frame from being inherited, so they restyle the
 * popup alone and not all it holds, as Base UI does.
 */
function registerProperties() {
  if (propertiesRegistered || typeof CSS === 'undefined' || !('registerProperty' in CSS)) {
    return
  }

  propertiesRegistered = true

  const properties = [
    ['--drawer-swipe-movement-x', '<length>', '0px'],
    ['--drawer-swipe-movement-y', '<length>', '0px'],
    ['--drawer-snap-point-offset', '<length>', '0px'],
    ['--drawer-swipe-progress', '<number>', '0'],
    ['--drawer-swipe-strength', '<number>', '1'],
  ] as const

  for (const [name, syntax, initialValue] of properties) {
    try {
      CSS.registerProperty({ name, syntax, inherits: false, initialValue })
    } catch {
      // Registered already, by another copy of this module.
    }
  }
}

let scrollLocks = 0
let scrollLockRestore: (() => void) | undefined

/** Holds the page from scrolling while a modal drawer is open, its scrollbar's room kept. */
function lockScroll() {
  scrollLocks += 1

  if (scrollLocks > 1) {
    return
  }

  const html = document.documentElement
  const { overflow, paddingRight } = html.style
  const scrollbar = window.innerWidth - html.clientWidth

  html.style.overflow = 'hidden'

  if (scrollbar > 0) {
    html.style.paddingRight = `calc(${getComputedStyle(html).paddingRight} + ${scrollbar}px)`
  }

  scrollLockRestore = () => {
    html.style.overflow = overflow
    html.style.paddingRight = paddingRight
  }
}

function unlockScroll() {
  scrollLocks = Math.max(0, scrollLocks - 1)

  if (scrollLocks === 0) {
    scrollLockRestore?.()
    scrollLockRestore = undefined
  }
}

function createDetails(reason: OpenChangeReason, event: Event | undefined): ChangeDetails {
  let canceled = false

  return {
    reason,
    event,
    cancel() {
      canceled = true
    },
    get isCanceled() {
      return canceled
    },
  }
}

function resolveFocus(target: FocusTarget | undefined) {
  return typeof target === 'function' ? target() : target
}

/** Every animation and transition running on `elements`, settled, finished or not. */
async function animationsSettled(elements: readonly (HTMLElement | null | undefined)[]) {
  // A frame on, so the transitions the attributes just set off have begun.
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
  await Promise.allSettled(
    elements.flatMap((element) =>
      element && typeof element.getAnimations === 'function'
        ? element.getAnimations().map((animation) => animation.finished)
        : [],
    ),
  )
}

export function createDrawer(options: DrawerOptions): Drawer {
  const { popup } = options
  const backdrop = options.backdrop ?? null
  const viewport = options.viewport ?? null
  const isDialog = popup instanceof HTMLDialogElement
  const modal = options.modal !== false
  const controller = new AbortController()
  const { signal } = controller

  options.signal?.addEventListener('abort', () => destroy(), { once: true })

  let swipeDirection: SwipeDirection = options.swipeDirection ?? 'down'
  let snapPoints = options.snapPoints
  let open = Boolean(options.defaultOpen)
  let mounted = open
  let ending: Promise<void> | undefined
  let starting = 0
  let parent: Drawer | null | undefined
  let returnFocusTo: HTMLElement | null = null
  let openListeners: AbortController | undefined
  let closeWatcher: { destroy(): void } | undefined
  let locked = false

  // The sizes it's measured at, and the state of the drawers opened over it.
  let popupHeight = 0
  let frontmostHeight = 0
  let viewportHeight = 0
  let rootFontSize = 16
  const nestedOpen = new Set<Drawer>()
  const nestedPresent = new Set<Drawer>()
  let nestedFrontmostOpen = false
  let nestedSwiping = false
  let nestedSwipeActive = false

  let activeSnapPoint: SnapPoint | null = getDefaultSnapPoint()
  let swipeStrength: number | null = null

  function getDefaultSnapPoint() {
    return options.defaultSnapPoint !== undefined
      ? options.defaultSnapPoint
      : (snapPoints?.[0] ?? null)
  }

  function hasSnapPoints() {
    return Boolean(snapPoints?.length) && getAxis(swipeDirection) === 'vertical'
  }

  function getMetrics() {
    return { popupHeight, rootFontSize, viewportHeight }
  }

  function getResolvedSnapPoints() {
    return hasSnapPoints() ? resolveSnapPoints(snapPoints, getMetrics()) : []
  }

  function getActiveSnapPointOffset() {
    return (
      findActiveSnapPoint(activeSnapPoint, getResolvedSnapPoints(), getMetrics())?.offset ?? null
    )
  }

  /** The two nearest-open points' offsets, between which the backdrop fades. */
  function getSnapPointRange() {
    const resolved = getResolvedSnapPoints()

    if (resolved.length < 2) {
      return null
    }

    const offsets = resolved.map((point) => point.offset).sort((a, b) => a - b)

    return { minOffset: offsets[0]!, range: offsets[1]! - offsets[0]! }
  }

  function offsetToProgress(offset: number) {
    const range = getSnapPointRange()

    return range ? clamp((offset - range.minOffset) / range.range, 0, 1) : 0
  }

  const nested = () => Boolean(parent)
  const swipeRoot = viewport ?? popup

  registerProperties()
  popup.setAttribute('data-drawer-popup', '')
  backdrop?.setAttribute('data-drawer-backdrop', '')
  viewport?.setAttribute('data-drawer-viewport', '')

  if (!isDialog && !popup.hasAttribute('role')) {
    popup.setAttribute('role', 'dialog')
  }

  if (!popup.hasAttribute('tabindex')) {
    popup.tabIndex = -1
  }

  backdrop?.setAttribute('role', 'presentation')

  // Drawn as it is now.

  function setFlag(element: Element | null, name: string, on: boolean) {
    element?.toggleAttribute(name, on)
  }

  function setVar(element: HTMLElement | null, name: string, value: string | undefined) {
    if (!element) {
      return
    }

    if (value === undefined) {
      element.style.removeProperty(name)
    } else {
      element.style.setProperty(name, value)
    }
  }

  function renderState() {
    const nestedCount = nestedOpen.size
    const hasNestedDrawer = nestedPresent.size > 0

    for (const element of [popup, backdrop]) {
      setFlag(element, 'data-open', open)
      setFlag(element, 'data-closed', !open)
      setFlag(element, 'data-snap-points', Boolean(snapPoints?.length))
    }

    popup.dataset.swipeDirection = swipeDirection
    popup.dataset.swipeAxis = getAxis(swipeDirection) === 'vertical' ? 'y' : 'x'
    setFlag(popup, 'data-nested', nested())
    setFlag(popup, 'data-nested-drawer-open', nestedCount > 0)
    setFlag(popup, 'data-nested-drawer-swiping', nestedSwiping)
    setFlag(popup, 'data-expanded', activeSnapPoint === 1)
    setVar(popup, '--nested-drawers', String(nestedCount))
    // Auto, but for a drawer under a nested one or going, held at what it was.
    setVar(
      popup,
      '--drawer-height',
      popupHeight && (hasNestedDrawer || ending) ? `${popupHeight}px` : undefined,
    )
    setVar(popup, '--drawer-frontmost-height', frontmostHeight ? `${frontmostHeight}px` : undefined)

    const offset = hasSnapPoints() ? getActiveSnapPointOffset() : null
    setVar(
      popup,
      '--drawer-snap-point-offset',
      offset === null ? '0px' : `${swipeDirection === 'up' ? -offset : offset}px`,
    )

    const strength =
      swipeStrength !== null && Number.isFinite(swipeStrength) && swipeStrength > 0
        ? String(swipeStrength)
        : '1'
    setVar(popup, '--drawer-swipe-strength', strength)
    setVar(backdrop, '--drawer-swipe-strength', strength)

    if (backdrop) {
      backdrop.style.pointerEvents = open ? '' : 'none'
    }
  }

  function setMovement(x: number, y: number) {
    setVar(popup, '--drawer-swipe-movement-x', `${x}px`)
    setVar(popup, '--drawer-swipe-movement-y', `${y}px`)
  }

  /** How far it's swiped toward closed, on the backdrop and to the drawer it's over. */
  function applySwipeProgress(progress: number, notifyParent: boolean) {
    const own = open && !nested() ? progress : 0

    if (notifyParent && parent) {
      const parentInternals = internals.get(parent)
      parentInternals?.onNestedSwipeProgressChange(open ? progress : 0)

      if (!open || progress <= 0) {
        finishNestedSwipe()
      }
    }

    setVar(backdrop, '--drawer-swipe-progress', own > 0 ? String(own) : '0')
  }

  /** At rest on a snap point, the backdrop as faded as the point is far from open. */
  function applyRestingProgress() {
    if (!getSnapPointRange() || swipe.swiping) {
      return
    }

    const offset = getActiveSnapPointOffset()
    applySwipeProgress(!open || nested() || offset === null ? 0 : offsetToProgress(offset), false)
  }

  // Measured as it changes.

  function measureHeight() {
    if (!mounted) {
      return
    }

    const height = popup.offsetHeight

    // Stretched to the height of a nested drawer over it: not its own.
    if (popupHeight > 0 && frontmostHeight > popupHeight && height > popupHeight) {
      return
    }

    // Held while a nested drawer is over it.
    if (popupHeight > 0 && nestedPresent.size > 0) {
      return
    }

    if (height === popupHeight) {
      return
    }

    popupHeight = height

    if (!nestedFrontmostOpen && height > 0) {
      setFrontmostHeight(height)
    }

    renderState()
    applyRestingProgress()
  }

  function setFrontmostHeight(height: number) {
    frontmostHeight = height

    if (open && parent) {
      internals.get(parent)?.onNestedFrontmostHeightChange(height)
    }
  }

  function measureViewport() {
    viewportHeight = viewport ? viewport.offsetHeight : document.documentElement.clientHeight
    const fontSize = Number.parseFloat(getComputedStyle(document.documentElement).fontSize)

    if (Number.isFinite(fontSize)) {
      rootFontSize = fontSize
    }

    renderState()
    applyRestingProgress()
  }

  if (typeof ResizeObserver === 'function') {
    const observer = new ResizeObserver(() => measureHeight())
    observer.observe(popup)
    signal.addEventListener('abort', () => observer.disconnect())

    if (viewport) {
      const viewportObserver = new ResizeObserver(() => measureViewport())
      viewportObserver.observe(viewport)
      signal.addEventListener('abort', () => viewportObserver.disconnect())
    }
  }

  if (!viewport) {
    window.addEventListener('resize', measureViewport, { signal })
  }

  // The drag.

  function finishNestedSwipe() {
    if (!nestedSwipeActive) {
      return
    }

    nestedSwipeActive = false

    if (parent) {
      internals.get(parent)?.onNestedSwipingChange(false)
    }
  }

  function setSwipeDismissed(dismissed: boolean) {
    setFlag(popup, 'data-swipe-dismiss', dismissed)
    setFlag(backdrop, 'data-swipe-dismiss', dismissed)
  }

  function clearSwipeRelease() {
    setSwipeDismissed(false)

    if (open) {
      setFlag(popup, 'data-ending-style', false)
    }

    swipeStrength = null
    renderState()
  }

  function setSwiping(swiping: boolean) {
    setFlag(popup, 'data-swiping', swiping)
    setFlag(backdrop, 'data-swiping', swiping)
  }

  /** How quickly it goes once let go, as a share of the full time: the faster, the less. */
  function resolveSwipeStrength(direction: SwipeDirection, release: SwipeRelease) {
    const size = getSwipeSize(popup, direction)

    if (size <= 0) {
      return null
    }

    const baseOffset =
      getAxis(direction) === 'vertical' && hasSnapPoints() ? (getActiveSnapPointOffset() ?? 0) : 0
    const remaining = Math.max(
      0,
      size - (baseOffset + getDisplacement(direction, release.deltaX, release.deltaY)),
    )

    if (remaining <= 0) {
      return null
    }

    const releaseVelocity = getDisplacement(
      direction,
      release.releaseVelocityX,
      release.releaseVelocityY,
    )
    const velocity =
      Math.abs(releaseVelocity) > 0
        ? releaseVelocity
        : getDisplacement(direction, release.velocityX, release.velocityY)

    if (velocity <= MIN_SWIPE_RELEASE_VELOCITY) {
      return null
    }

    const duration = clamp(
      remaining / clamp(velocity, MIN_SWIPE_RELEASE_VELOCITY, MAX_SWIPE_RELEASE_VELOCITY),
      MIN_SWIPE_RELEASE_DURATION_MS,
      MAX_SWIPE_RELEASE_DURATION_MS,
    )
    const normalized =
      (duration - MIN_SWIPE_RELEASE_DURATION_MS) /
      (MAX_SWIPE_RELEASE_DURATION_MS - MIN_SWIPE_RELEASE_DURATION_MS)

    return (
      MIN_SWIPE_RELEASE_SCALAR + normalized * (MAX_SWIPE_RELEASE_SCALAR - MIN_SWIPE_RELEASE_SCALAR)
    )
  }

  /** Goes on from where it was let go at once, rather than sitting still for a frame. */
  function startSwipeRelease(direction: SwipeDirection, release: SwipeRelease) {
    finishNestedSwipe()
    setSwipeDismissed(true)
    setFlag(popup, 'data-ending-style', true)
    setFlag(backdrop, 'data-ending-style', true)
    swipeStrength = resolveSwipeStrength(direction, release)
    renderState()
  }

  function getSwipeThreshold(direction: SwipeDirection) {
    return Math.max(getSwipeSize(popup, direction) * 0.5, MIN_SWIPE_THRESHOLD)
  }

  function onRelease(release: SwipeRelease): boolean | undefined {
    const { direction, deltaX, deltaY } = release

    if (!hasSnapPoints()) {
      if (!direction) {
        clearSwipeRelease()
        return undefined
      }

      const delta = getDisplacement(direction, deltaX, deltaY)

      if (delta <= 0) {
        clearSwipeRelease()
        return false
      }

      const shouldClose =
        getDisplacement(direction, release.velocityX, release.velocityY) >= FAST_SWIPE_VELOCITY ||
        delta > getSwipeThreshold(direction)

      if (shouldClose) {
        startSwipeRelease(direction, release)
      } else {
        clearSwipeRelease()
      }

      return shouldClose
    }

    const resolved = getResolvedSnapPoints()

    if (!popupHeight) {
      clearSwipeRelease()
      return false
    }

    if (resolved.length === 0) {
      clearSwipeRelease()
      return undefined
    }

    const down = swipeDirection === 'down'
    const dragDelta = down ? deltaY : -deltaY
    const dragDirection = Math.sign(dragDelta)
    const fallbackVelocity = down ? release.velocityY : -release.velocityY
    let velocity = down ? release.releaseVelocityY : -release.releaseVelocityY

    // A finger turning back at the very end doesn't flip where it goes.
    if (dragDirection !== 0 && Math.abs(dragDelta) >= MIN_SWIPE_THRESHOLD) {
      const velocityDirection = Math.sign(velocity)

      if (velocityDirection !== 0 && velocityDirection !== dragDirection) {
        velocity = fallbackVelocity
      }
    }

    const currentOffset = getActiveSnapPointOffset() ?? 0
    const dragTarget = clamp(currentOffset + dragDelta, 0, popupHeight)
    const velocityOffset =
      Math.abs(velocity) >= SNAP_VELOCITY_THRESHOLD
        ? clamp(velocity, -MAX_SNAP_VELOCITY, MAX_SNAP_VELOCITY) * SNAP_VELOCITY_MULTIPLIER
        : 0
    const target = options.snapToSequentialPoints
      ? dragTarget
      : clamp(dragTarget + velocityOffset, 0, popupHeight)

    const settleInPlace = () => {
      applySwipeProgress(0, true)
      clearSwipeRelease()
      return false
    }

    const settleOn = (point: ResolvedSnapPoint) => {
      setSnapPoint(point.value, release.event)
      return settleInPlace()
    }

    const closeFrom = (fallback: ResolvedSnapPoint) => {
      // A drag that went no way in particular may settle, but never closes.
      if (!direction) {
        return settleOn(fallback)
      }

      const details = createDetails('swipe', release.event)
      options.onSnapPointChange?.(null, details)

      if (details.isCanceled) {
        return settleInPlace()
      }

      startSwipeRelease(swipeDirection, release)
      return true
    }

    if (options.snapToSequentialPoints) {
      const ordered = [...resolved].sort((a, b) => a.offset - b.offset)
      const offsets = ordered.map((point) => point.offset)
      const currentIndex = closestIndex(offsets, currentOffset)
      let targetPoint = ordered[closestIndex(offsets, target)]!
      let effectiveTarget = target
      const velocityDirection = Math.sign(velocity)

      if (
        dragDirection !== 0 &&
        velocityDirection === dragDirection &&
        Math.abs(velocity) >= SNAP_VELOCITY_THRESHOLD
      ) {
        const adjacentIndex = clamp(currentIndex + dragDirection, 0, ordered.length - 1)

        if (adjacentIndex !== currentIndex) {
          const adjacent = ordered[adjacentIndex]!

          if (dragDirection > 0 ? target < adjacent.offset : target > adjacent.offset) {
            targetPoint = adjacent
            effectiveTarget = adjacent.offset
          }
        } else if (dragDirection > 0) {
          return closeFrom(targetPoint)
        }
      }

      return Math.abs(effectiveTarget - popupHeight) <
        Math.abs(effectiveTarget - targetPoint.offset)
        ? closeFrom(targetPoint)
        : settleOn(targetPoint)
    }

    const closest =
      resolved[
        closestIndex(
          resolved.map((point) => point.offset),
          target,
        )
      ]!

    if (velocity >= FAST_SWIPE_VELOCITY && dragDelta > 0) {
      return closeFrom(closest)
    }

    return Math.abs(target - popupHeight) < Math.abs(target - closest.offset)
      ? closeFrom(closest)
      : settleOn(closest)
  }

  const swipe = createSwipe({
    root: swipeRoot,
    popup,
    getDirections: () =>
      hasSnapPoints()
        ? swipeDirection === 'down'
          ? ['down', 'up']
          : ['up', 'down']
        : [swipeDirection],
    isEnabled: () => mounted && open && nestedOpen.size === 0,
    onStart() {
      setSwiping(true)
    },
    onMove({ deltaX, deltaY, direction, progress }) {
      let movementY = deltaY

      if (hasSnapPoints() && swipeDirection === 'down') {
        movementY = getSnapPointSwipeMovement(getActiveSnapPointOffset() ?? 0, deltaY)
      }

      setMovement(deltaX, movementY)

      // A drawer under this one follows it once it has gone a little way.
      if (
        !nestedSwipeActive &&
        parent &&
        Math.abs(getDisplacement(direction ?? swipeDirection, deltaX, deltaY)) >=
          MIN_SWIPE_THRESHOLD
      ) {
        nestedSwipeActive = true
        internals.get(parent)?.onNestedSwipingChange(true)
      }

      let resolvedProgress = progress
      const range = getSnapPointRange()

      if (range && popupHeight > 0) {
        const base = getActiveSnapPointOffset() ?? range.minOffset
        resolvedProgress = offsetToProgress(clamp(base + deltaY, 0, popupHeight))
      }

      applySwipeProgress(resolvedProgress, true)
    },
    onRelease,
    onDismiss(event) {
      setSwiping(false)
      // Closed by this, or by its owner from in `onOpenChange`.
      setOpen(false, 'swipe', event)

      if (open) {
        // Kept open: back where it was.
        clearSwipeRelease()
        settle()
      }
    },
    onSettle() {
      setSwiping(false)
      settle()
    },
    signal,
  })

  function settle() {
    setMovement(0, 0)
    applySwipeProgress(0, true)
    finishNestedSwipe()
    applyRestingProgress()
  }

  // Opening and closing.

  function resolveParent() {
    const value = options.parent

    return (typeof value === 'function' ? value() : value) ?? null
  }

  function isTopmost() {
    return nestedOpen.size === 0
  }

  function containsTarget(target: Node) {
    if (popup.contains(target)) {
      return true
    }

    for (const child of nestedPresent) {
      if (internals.get(child)?.contains(target)) {
        return true
      }
    }

    return false
  }

  function listenWhileOpen() {
    openListeners?.abort()
    openListeners = new AbortController()
    const listenerSignal = AbortSignal.any([openListeners.signal, signal])

    swipe.listen(true)

    if (isDialog) {
      // Escape closes this drawer alone, not what the page behind would close with it: the
      // keydown, which reaches the page before the dialog's cancel, is taken here.
      popup.addEventListener(
        'keydown',
        (event) => {
          if (event.key !== 'Escape' || event.defaultPrevented) {
            return
          }

          event.preventDefault()

          if (!options.disableEscapeDismissal && isTopmost()) {
            setOpen(false, 'escape-key', event)
          }
        },
        { signal: listenerSignal },
      )
      // Any other request to close it, as Android's back gesture.
      popup.addEventListener(
        'cancel',
        (event) => {
          event.preventDefault()

          if (!options.disableEscapeDismissal && isTopmost()) {
            setOpen(false, 'escape-key', event)
          }
        },
        { signal: listenerSignal },
      )
    } else if (!options.disableEscapeDismissal) {
      document.addEventListener(
        'keydown',
        (event) => {
          if (event.key !== 'Escape' || event.defaultPrevented || !isTopmost()) {
            return
          }

          // From in a dialog over it, as a lightbox, Escape is that dialog's.
          const target = event.target instanceof Element ? event.target : null
          const dialog = target?.closest('dialog[open]')

          if (dialog && !popup.contains(dialog)) {
            return
          }

          event.preventDefault()
          setOpen(false, 'escape-key', event)
        },
        { signal: listenerSignal },
      )
    }

    if (!options.disablePointerDismissal) {
      if (isDialog && modal) {
        // Outside a modal dialog's box is its ::backdrop, which is the dialog to a click.
        popup.addEventListener(
          'click',
          (event) => {
            const box = popup.getBoundingClientRect()
            const outside =
              event.target === popup &&
              (event.clientX < box.left ||
                event.clientX > box.right ||
                event.clientY < box.top ||
                event.clientY > box.bottom)

            if (outside && isTopmost()) {
              setOpen(false, 'outside-press', event)
            }
          },
          { signal: listenerSignal },
        )
      } else {
        document.addEventListener(
          'pointerdown',
          (event) => {
            if (
              event.button === 0 &&
              isTopmost() &&
              event.target instanceof Node &&
              !containsTarget(event.target) &&
              !(backdrop && backdrop.contains(event.target))
            ) {
              setOpen(false, 'outside-press', event)
            }
          },
          { signal: listenerSignal },
        )
      }
    }

    // Android's back gesture, in Chromium, for a popup that isn't a <dialog>.
    const CloseWatcher = (window as { CloseWatcher?: new () => EventTarget & { destroy(): void } })
      .CloseWatcher

    if (
      !isDialog &&
      !options.disableEscapeDismissal &&
      CloseWatcher &&
      /Android/i.test(navigator.userAgent)
    ) {
      const watcher = new CloseWatcher()
      watcher.addEventListener('cancel', (event) => {
        if (open) {
          setOpen(false, 'close-watcher', event)

          if (open && event.cancelable) {
            event.preventDefault()
          }
        }
      })
      closeWatcher = watcher
    }
  }

  function stopListeningWhileOpen() {
    swipe.listen(false)
    openListeners?.abort()
    openListeners = undefined
    closeWatcher?.destroy()
    closeWatcher = undefined
  }

  function showPopup() {
    if (isDialog) {
      if (!popup.open) {
        if (modal) {
          popup.showModal()
        } else {
          popup.show()
        }
      }
    } else {
      popup.hidden = false
    }

    if (backdrop) {
      backdrop.hidden = false
    }

    if (modal && !locked) {
      locked = true
      lockScroll()
    }
  }

  function hidePopup() {
    if (isDialog) {
      popup.close()
    } else {
      popup.hidden = true
    }

    if (backdrop) {
      backdrop.hidden = true
    }

    if (locked) {
      locked = false
      unlockScroll()
    }
  }

  function focus(target: HTMLElement | null | false | undefined) {
    if (target && target.isConnected) {
      target.focus({ preventScroll: true })
    }
  }

  function setOpen(next: boolean, reason: OpenChangeReason, event?: Event) {
    if (next === open) {
      return false
    }

    const details = createDetails(reason, event)
    options.onOpenChange?.(next, details)

    if (details.isCanceled || next === open) {
      return false
    }

    if (next) {
      enter()
    } else {
      leave()
    }

    for (const trigger of options.triggers ?? []) {
      trigger.setAttribute('aria-expanded', String(open))
    }

    return true
  }

  function enter() {
    const wasMounted = mounted

    open = true
    mounted = true
    ending = undefined
    returnFocusTo = document.activeElement instanceof HTMLElement ? document.activeElement : null

    parent = resolveParent()

    if (parent) {
      internals.get(parent)?.onNestedOpenChange(api, true)
      internals.get(parent)?.onNestedPresenceChange(api, true)
    }

    showPopup()
    swipe.reset()
    setMovement(0, 0)
    clearSwipeRelease()
    setFlag(popup, 'data-ending-style', false)
    setFlag(backdrop, 'data-ending-style', false)

    if (!wasMounted) {
      // Comes in from where it would be closed: styled so, the style taken, then let go.
      setFlag(popup, 'data-starting-style', true)
      setFlag(backdrop, 'data-starting-style', true)
      renderState()
      void popup.offsetHeight

      const frame = requestAnimationFrame(() => {
        if (starting === frame) {
          starting = 0
          setFlag(popup, 'data-starting-style', false)
          setFlag(backdrop, 'data-starting-style', false)
        }
      })
      starting = frame
    } else {
      renderState()
    }

    measureViewport()
    measureHeight()
    listenWhileOpen()

    const initial = options.initialFocus === undefined ? popup : resolveFocus(options.initialFocus)
    focus(initial)

    void animationsSettled([popup, backdrop]).then(() => {
      if (open) {
        options.onOpenChangeComplete?.(true)
      }
    })
  }

  function leave() {
    open = false

    if (starting) {
      cancelAnimationFrame(starting)
      starting = 0
      setFlag(popup, 'data-starting-style', false)
      setFlag(backdrop, 'data-starting-style', false)
    }

    stopListeningWhileOpen()

    if (parent) {
      internals.get(parent)?.onNestedOpenChange(api, false)
      internals.get(parent)?.onNestedFrontmostHeightChange(0)
    }

    applySwipeProgress(0, true)
    setFlag(popup, 'data-ending-style', true)
    setFlag(backdrop, 'data-ending-style', true)

    const run = animationsSettled([popup, backdrop]).then(() => {
      if (ending !== run || open) {
        return
      }

      ending = undefined
      mounted = false
      hidePopup()
      setFlag(popup, 'data-ending-style', false)
      setFlag(backdrop, 'data-ending-style', false)
      setSwipeDismissed(false)
      swipe.reset()
      setMovement(0, 0)
      swipeStrength = null
      activeSnapPoint = getDefaultSnapPoint()
      popupHeight = 0
      frontmostHeight = 0

      if (parent) {
        internals.get(parent)?.onNestedPresenceChange(api, false)
        parent = null
      }

      renderState()

      const final =
        options.finalFocus === undefined ? returnFocusTo : resolveFocus(options.finalFocus)
      returnFocusTo = null

      // Only from inside: focus that has moved on elsewhere stays there.
      const active = document.activeElement

      if (!active || active === document.body || popup.contains(active)) {
        focus(final)
      }

      options.onOpenChangeComplete?.(false)
    })

    ending = run
    renderState()

    // Back from its snap point to the first, for the next time, as Base UI does.
    if (hasSnapPoints()) {
      options.onSnapPointChange?.(getDefaultSnapPoint(), createDetails('none', undefined))
    }
  }

  function setSnapPoint(value: SnapPoint | null, event?: Event) {
    if (Object.is(value, activeSnapPoint)) {
      return
    }

    const details = createDetails(event ? 'swipe' : 'none', event)
    options.onSnapPointChange?.(value, details)

    if (details.isCanceled) {
      return
    }

    activeSnapPoint = value
    renderState()
    applyRestingProgress()
  }

  // Wired to the page.

  for (const trigger of options.triggers ?? []) {
    trigger.setAttribute('aria-haspopup', 'dialog')

    if (popup.id) {
      trigger.setAttribute('aria-controls', popup.id)
    }

    trigger.addEventListener('click', (event) => setOpen(!open, 'trigger-press', event), { signal })
  }

  // A close button of this drawer's, not one of a drawer nested inside it.
  popup.addEventListener(
    'click',
    (event) => {
      const button =
        event.target instanceof Element ? event.target.closest('[data-drawer-close]') : null

      if (!button || !popup.contains(button)) {
        return
      }

      for (const child of nestedPresent) {
        if (child.popup.contains(button)) {
          return
        }
      }

      setOpen(false, 'close-press', event)
    },
    { signal },
  )

  backdrop?.addEventListener(
    'click',
    (event) => {
      // A link for the page without script: a click on it closes, rather than leaves.
      event.preventDefault()

      if (!options.disablePointerDismissal && open && isTopmost()) {
        setOpen(false, 'outside-press', event)
      }
    },
    { signal },
  )

  function destroy() {
    if (signal.aborted) {
      return
    }

    controller.abort()
    stopListeningWhileOpen()

    if (locked) {
      locked = false
      unlockScroll()
    }

    if (parent) {
      internals.get(parent)?.onNestedOpenChange(api, false)
      internals.get(parent)?.onNestedPresenceChange(api, false)
    }

    // The elements as they were, but shown or hidden as the drawer last had them.
    for (const element of [popup, backdrop, viewport]) {
      for (const name of [...(element?.attributes ?? [])].map((attribute) => attribute.name)) {
        if (DRAWER_ATTRIBUTES.has(name)) {
          element?.removeAttribute(name)
        }
      }

      // A copy: the live list shrinks as they go.
      for (const name of Array.from(element?.style ?? [])) {
        if (name.startsWith('--drawer-') || name === '--nested-drawers') {
          element?.style.removeProperty(name)
        }
      }
    }

    if (backdrop) {
      backdrop.style.pointerEvents = ''
    }
  }

  const api: Drawer = {
    popup,
    get isOpen() {
      return open
    },
    get isMounted() {
      return mounted
    },
    get snapPoint() {
      return activeSnapPoint
    },
    get swipeDirection() {
      return swipeDirection
    },
    open: (event, reason = 'none') => setOpen(true, reason, event),
    close: (event, reason = 'none') => setOpen(false, reason, event),
    setSnapPoint,
    setSnapPoints(next) {
      snapPoints = next

      if (!next?.some((point) => Object.is(point, activeSnapPoint))) {
        activeSnapPoint = getDefaultSnapPoint()
      }

      renderState()
      applyRestingProgress()
    },
    setSwipeDirection(direction) {
      swipeDirection = direction
      renderState()
      applyRestingProgress()
    },
    getVisibleSize() {
      const size = getSwipeSize(popup, swipeDirection)
      const offset = hasSnapPoints() ? (getActiveSnapPointOffset() ?? 0) : 0

      return Math.max(0, size - offset)
    },
    destroy,
  }

  internals.set(api, {
    onNestedOpenChange(child, childOpen) {
      if (childOpen) {
        nestedOpen.add(child)
      } else {
        nestedOpen.delete(child)
      }

      if (!childOpen && nestedOpen.size === 0) {
        nestedSwiping = false
        setVar(popup, '--drawer-swipe-progress', '0')
      }

      renderState()
    },
    onNestedPresenceChange(child, present) {
      if (present) {
        nestedPresent.add(child)
      } else {
        nestedPresent.delete(child)
        measureHeight()
      }

      renderState()
    },
    onNestedFrontmostHeightChange(height) {
      if (height > 0) {
        nestedFrontmostOpen = true
        setFrontmostHeight(height)
      } else {
        nestedFrontmostOpen = false

        if (popupHeight > 0) {
          setFrontmostHeight(popupHeight)
        }
      }

      renderState()
    },
    onNestedSwipingChange(swiping) {
      nestedSwiping = swiping
      renderState()

      if (parent) {
        internals.get(parent)?.onNestedSwipingChange(swiping)
      }
    },
    onNestedSwipeProgressChange(progress) {
      setVar(popup, '--drawer-swipe-progress', progress > 0 ? String(progress) : '0')

      if (parent) {
        internals.get(parent)?.onNestedSwipeProgressChange(progress)
      }
    },
    contains: containsTarget,
  })

  // As the page rendered it.
  if (open) {
    showPopup()
    parent = resolveParent()

    if (parent) {
      internals.get(parent)?.onNestedOpenChange(api, true)
      internals.get(parent)?.onNestedPresenceChange(api, true)
    }

    listenWhileOpen()
  } else if (!isDialog) {
    popup.hidden = true

    if (backdrop) {
      backdrop.hidden = true
    }
  }

  setMovement(0, 0)
  setVar(backdrop, '--drawer-swipe-progress', '0')
  setVar(popup, '--drawer-swipe-progress', '0')

  for (const trigger of options.triggers ?? []) {
    trigger.setAttribute('aria-expanded', String(open))
  }

  measureViewport()
  measureHeight()

  return api
}
