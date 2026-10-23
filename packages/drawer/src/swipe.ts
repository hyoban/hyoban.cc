/**
 * @license MIT
 * Ported from Base UI utils/useSwipeDismiss, utils/scrollable and the touch handling
 * of drawer/viewport/DrawerViewport
 * (@base-ui/react 1.9.0, https://github.com/mui/base-ui), Copyright (c) 2019 Material-UI SAS.
 * Changes Copyright (c) 2026 Stephen Zhou. See LICENSE and THIRD-PARTY-NOTICES.md.
 */

// The drag that swipes a drawer away or between its snap points: Base UI's useSwipeDismiss
// and the touch handling of its Drawer.Viewport, on native events. A mouse or a pen drags
// with pointer events, from anywhere but the content (`data-drawer-content`); a finger
// drags with touch events from anywhere, a scroller in the drawer's way scrolling first
// until it reaches its edge, and a scroller across the drawer's axis keeping a gesture
// that goes its way.

export type SwipeDirection = 'up' | 'down' | 'left' | 'right'

export type SwipeMove = {
  /** The drag so far, resisting (by its square root) where it goes the wrong way. */
  deltaX: number
  deltaY: number
  direction: SwipeDirection | undefined
  /** How far toward closed, as a share of the popup's size. */
  progress: number
}

export type SwipeRelease = {
  event: Event
  direction: SwipeDirection | undefined
  deltaX: number
  deltaY: number
  /** Over the whole drag, in pixels a millisecond. */
  velocityX: number
  velocityY: number
  /** Over the last moments of the drag. */
  releaseVelocityX: number
  releaseVelocityY: number
}

export type SwipeOptions = {
  /** Where the presses are heard: the viewport around the popup, or the popup itself. */
  root: HTMLElement
  popup: HTMLElement
  /** The way it closes first, then the other way along the axis when it has snap points. */
  getDirections: () => readonly SwipeDirection[]
  isEnabled: () => boolean
  onStart: () => void
  onMove: (move: SwipeMove) => void
  /** Whether the drag swipes the drawer away; `undefined` leaves it to how far it went. */
  onRelease: (release: SwipeRelease) => boolean | undefined
  onDismiss: (event: Event, direction: SwipeDirection) => void
  /** The drag let go without closing, or given up, the popup back where it was. */
  onSettle: () => void
  signal: AbortSignal
}

const DEFAULT_SWIPE_THRESHOLD = 40
const REVERSE_CANCEL_THRESHOLD = 10
const MIN_VELOCITY_DURATION_MS = 50
// Under one frame at 240 Hz, so it only damps near-simultaneous samples.
const MIN_RELEASE_VELOCITY_DURATION_MS = 4
const MAX_RELEASE_VELOCITY_AGE_MS = 80
const MIN_VELOCITY_SAMPLE_DISTANCE = 1
const AXIS_LOCK_SLOP = 6
const AXIS_LOCK_BIAS = 2
const IGNORE_SELECTOR = 'button,a,input,select,textarea,label,[role="button"]'
const SWIPE_IGNORE = '[data-swipe-ignore]'
const CONTENT = '[data-drawer-content]'

type Axis = 'horizontal' | 'vertical'
type Point = { x: number; y: number }
type Sample = Point & { time: number }

type TouchState = {
  start: Point
  last: Point
  startedInPopup: boolean
  scrollTarget: HTMLElement | null
  hasCrossAxisGestureTarget: boolean
  allowSwipe: boolean | null
  preserveNativeCrossAxisScroll: boolean
  drawerAxisAttributed: boolean
}

export function getDisplacement(
  direction: SwipeDirection | undefined,
  deltaX: number,
  deltaY: number,
) {
  switch (direction) {
    case 'up':
      return -deltaY
    case 'down':
      return deltaY
    case 'left':
      return -deltaX
    case 'right':
      return deltaX
    default:
      return 0
  }
}

export function getSwipeSize(element: HTMLElement, direction: SwipeDirection | undefined) {
  return direction === 'left' || direction === 'right' ? element.offsetWidth : element.offsetHeight
}

export function getAxis(direction: SwipeDirection): Axis {
  return direction === 'left' || direction === 'right' ? 'horizontal' : 'vertical'
}

function isScrollable(element: Element, axis: Axis) {
  const style = getComputedStyle(element)
  const overflow = axis === 'vertical' ? style.overflowY : style.overflowX

  if (overflow !== 'auto' && overflow !== 'scroll') {
    return false
  }

  return axis === 'vertical'
    ? element.scrollHeight > element.clientHeight
    : element.scrollWidth > element.clientWidth
}

/** The nearest element from `target` up to (not including) `root` that scrolls along `axis`. */
export function findScrollableAncestor(target: Element | null, root: Element, axis: Axis) {
  let node: Element | null = target

  while (node && node !== root && node !== document.documentElement) {
    if (node instanceof HTMLElement && isScrollable(node, axis)) {
      return node
    }

    node = node.parentElement
  }

  return null
}

function findScrollableTouchTarget(target: Element | null, root: HTMLElement, axis: Axis) {
  return findScrollableAncestor(target, root, axis) ?? (isScrollable(root, axis) ? root : null)
}

function getScrollMetrics(element: HTMLElement, axis: Axis) {
  return axis === 'vertical'
    ? { offset: element.scrollTop, max: Math.max(0, element.scrollHeight - element.clientHeight) }
    : { offset: element.scrollLeft, max: Math.max(0, element.scrollWidth - element.clientWidth) }
}

/** Swiping down or right starts from a scroller's start, up or left from its end. */
function dismissesFromStartEdge(direction: SwipeDirection, axis: Axis) {
  return axis === 'vertical' ? direction === 'down' : direction === 'right'
}

function isAtSwipeStartEdge(element: HTMLElement, axis: Axis, direction: SwipeDirection) {
  const { offset, max } = getScrollMetrics(element, axis)

  return dismissesFromStartEdge(direction, axis) ? offset <= 0 : offset >= max
}

function elementAt(root: Node, x: number, y: number) {
  const host = root as Document | ShadowRoot

  return typeof host.elementFromPoint === 'function' ? host.elementFromPoint(x, y) : null
}

function getEventTarget(event: Event) {
  const target = event.composedPath?.()[0] ?? event.target

  return target instanceof Element ? target : null
}

/** Whether text is selected in `root`, which a drag would otherwise steal. */
function hasSelectionWithin(root: HTMLElement) {
  const active = document.activeElement

  if (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement) {
    const { selectionStart, selectionEnd } = active

    if (
      root.contains(active) &&
      selectionStart != null &&
      selectionEnd != null &&
      selectionStart < selectionEnd
    ) {
      return true
    }
  }

  const selection = document.getSelection()

  if (!selection || selection.isCollapsed) {
    return false
  }

  const anchor =
    selection.anchorNode instanceof Element
      ? selection.anchorNode
      : selection.anchorNode?.parentElement
  const focus =
    selection.focusNode instanceof Element
      ? selection.focusNode
      : selection.focusNode?.parentElement

  return (
    selection.containsNode(root, true) ||
    Boolean(anchor && root.contains(anchor)) ||
    Boolean(focus && root.contains(focus))
  )
}

export function createSwipe(options: SwipeOptions) {
  const { root, popup, signal } = options

  let swiping = false
  let pending = false
  let pendingStart: Point | null = null
  let fromScrollable = false
  let sawPrimaryButtons = false
  let firstMove = false
  let start: Point = { x: 0, y: 0 }
  let lastMove: Point | null = null
  let offset: Point = { x: 0, y: 0 }
  let cancelBaseline: Point = { x: 0, y: 0 }
  let intended: SwipeDirection | undefined
  let maxDisplacement = 0
  let cancelled = false
  let size = { width: 0, height: 0 }
  let startTime: number | null = null
  let lastSample: Sample | null = null
  let stationarySample = false
  let lastVelocity: Point = { x: 0, y: 0 }
  let lastPointerType = ''
  let ignoreTouchStartFromPen = false
  let ignoreTouch = false
  let touchState: TouchState | null = null

  const directions = () => options.getDirections()
  const primaryDirection = () => directions()[0]
  const axis = () => getAxis(primaryDirection() ?? 'down')
  const crossAxis = (): Axis => (axis() === 'vertical' ? 'horizontal' : 'vertical')
  const allows = (direction: SwipeDirection) => directions().includes(direction)

  function validTime(time: number) {
    return Number.isFinite(time) && time > 0 ? time : null
  }

  function findGestureScrollable(target: Element | null, boundary: HTMLElement) {
    // The page's own scroller doesn't hold the drag back: the popup is placed on the viewport.
    const found = findScrollableTouchTarget(target, boundary, axis())

    return found === document.body || found === document.documentElement ? null : found
  }

  function recordSample(point: Point, time: number | null) {
    if (time === null) {
      return
    }

    if (lastSample && time > lastSample.time) {
      // Some Android phones send one move standing still right before the finger lifts;
      // it keeps the last moving sample, and a second one in a row clears the speed.
      const stationary =
        Math.abs(point.x - lastSample.x) < MIN_VELOCITY_SAMPLE_DISTANCE &&
        Math.abs(point.y - lastSample.y) < MIN_VELOCITY_SAMPLE_DISTANCE
      const skip = stationary && !stationarySample

      stationarySample = stationary

      if (skip) {
        return
      }

      const duration = Math.max(time - lastSample.time, MIN_RELEASE_VELOCITY_DURATION_MS)
      lastVelocity = {
        x: (point.x - lastSample.x) / duration,
        y: (point.y - lastSample.y) / duration,
      }
    }

    lastSample = { ...point, time }
  }

  function resetPending() {
    pending = false
    pendingStart = null
    fromScrollable = false
    lastMove = null
  }

  function damp(deltaX: number, deltaY: number): Point {
    const root = (value: number) => Math.sign(value) * Math.abs(value) ** 0.5
    const axisDamp = (delta: number, negative: boolean, positive: boolean) =>
      (!negative && delta < 0) || (!positive && delta > 0) ? root(delta) : delta
    const horizontal = allows('left') || allows('right')
    const vertical = allows('up') || allows('down')

    return {
      x: horizontal ? axisDamp(deltaX, allows('left'), allows('right')) : 0,
      y: vertical ? axisDamp(deltaY, allows('up'), allows('down')) : 0,
    }
  }

  function canStart(position: Point, event: Event) {
    const touchLike = event instanceof TouchEvent || (event as PointerEvent).pointerType === 'touch'
    const touch = touchLike ? touchState : null
    const atPoint = elementAt(popup.getRootNode(), position.x, position.y)

    // A touch that began in the popup may go on outside it.
    if (!touch?.startedInPopup && !(atPoint && popup.contains(atPoint))) {
      return false
    }

    if (touchLike && hasSelectionWithin(popup)) {
      return false
    }

    // Over something that scrolls across, wait until the gesture goes the drawer's way.
    return !(
      touch?.hasCrossAxisGestureTarget &&
      !touch.drawerAxisAttributed &&
      touch.allowSwipe !== true
    )
  }

  function startAt(
    event: PointerEvent | TouchEvent,
    position: Point,
    startOptions: { ignoreScrollable?: boolean } = {},
  ) {
    fromScrollable = false
    const touchLike = event instanceof TouchEvent || event.pointerType === 'touch'
    const target = elementAt(popup.getRootNode(), position.x, position.y) ?? getEventTarget(event)
    const scrollable = touchLike ? findGestureScrollable(target, document.body) : null

    if (scrollable && !startOptions.ignoreScrollable) {
      return false
    }

    fromScrollable = Boolean(scrollable && startOptions.ignoreScrollable)

    // A finger drags from a button too; a mouse clicks it.
    if (!touchLike && target?.closest(IGNORE_SELECTOR)) {
      return false
    }

    if (!startOptions.ignoreScrollable && findScrollableAncestor(target, popup, axis())) {
      return false
    }

    cancelled = false
    intended = undefined
    maxDisplacement = 0
    start = position
    startTime = validTime(event.timeStamp)
    lastSample = null
    stationarySample = false
    lastVelocity = { x: 0, y: 0 }
    cancelBaseline = position
    lastMove = position
    offset = { x: 0, y: 0 }
    size = { width: popup.offsetWidth, height: popup.offsetHeight }
    recordSample(offset, startTime)

    if (!(event instanceof TouchEvent)) {
      try {
        popup.setPointerCapture(event.pointerId)
      } catch {}

      // A drag with a mouse lets go of text selected in the drawer.
      const selection = document.getSelection()

      if (selection && !selection.isCollapsed && hasSelectionWithin(popup)) {
        selection.removeAllRanges()
      }
    }

    swiping = true
    firstMove = true
    options.onStart()
    return true
  }

  function handleStart(event: PointerEvent | TouchEvent, position: Point) {
    if (!options.isEnabled() || event.defaultPrevented) {
      return
    }

    if (!(event instanceof TouchEvent) && event.button !== 0) {
      return
    }

    pending = true
    pendingStart = position
    fromScrollable = false
    sawPrimaryButtons = !(event instanceof TouchEvent)

    if (!canStart(position, event)) {
      return
    }

    if (startAt(event, position)) {
      pending = false
      pendingStart = null
    }
  }

  function moveCore(event: PointerEvent | TouchEvent, position: Point, movement: Point) {
    if (!options.isEnabled() || !swiping) {
      return
    }

    const touchLike = event instanceof TouchEvent || event.pointerType === 'touch'

    if (touchLike && !fromScrollable && findGestureScrollable(getEventTarget(event), root)) {
      return
    }

    if (!(event instanceof TouchEvent)) {
      // No text selected along the way, in Safari.
      event.preventDefault()
    }

    if (firstMove) {
      // From the first move on, as iOS sends the first touchmove some way from the
      // touchstart, which would make the popup jump.
      firstMove = false
      start = position
      startTime = validTime(event.timeStamp) ?? startTime
      lastSample = null
      stationarySample = false
    }

    if (
      (movement.y < 0 && position.y > cancelBaseline.y) ||
      (movement.y > 0 && position.y < cancelBaseline.y)
    ) {
      cancelBaseline = { x: cancelBaseline.x, y: position.y }
    }

    if (
      (movement.x < 0 && position.x > cancelBaseline.x) ||
      (movement.x > 0 && position.x < cancelBaseline.x)
    ) {
      cancelBaseline = { x: position.x, y: cancelBaseline.y }
    }

    const deltaX = position.x - start.x
    const deltaY = position.y - start.y

    if (!intended) {
      const candidate: SwipeDirection =
        Math.abs(deltaX) >= Math.abs(deltaY)
          ? deltaX > 0
            ? 'right'
            : 'left'
          : deltaY > 0
            ? 'down'
            : 'up'

      if (allows(candidate)) {
        intended = candidate
        maxDisplacement = getDisplacement(candidate, deltaX, deltaY)
      }
    } else {
      const current = getDisplacement(
        intended,
        position.x - cancelBaseline.x,
        position.y - cancelBaseline.y,
      )

      if (current > DEFAULT_SWIPE_THRESHOLD) {
        cancelled = false
      } else if (
        !(allows('left') && allows('right')) &&
        !(allows('up') && allows('down')) &&
        maxDisplacement - current >= REVERSE_CANCEL_THRESHOLD
      ) {
        // Changed its mind, back the way it came.
        cancelled = true
      }
    }

    offset = damp(deltaX, deltaY)
    recordSample(offset, validTime(event.timeStamp))

    const direction = primaryDirection() ?? intended
    const along = direction === 'left' || direction === 'right' ? size.width : size.height
    const displacement = getDisplacement(direction, offset.x, offset.y)

    options.onMove({
      deltaX: offset.x,
      deltaY: offset.y,
      direction: intended,
      progress: along > 0 && displacement > 0 ? Math.min(displacement / along, 1) : 0,
    })
  }

  function handleEnd(event: PointerEvent | TouchEvent) {
    if (!options.isEnabled() && !swiping) {
      return
    }

    if (!swiping) {
      resetPending()
      return
    }

    swiping = false
    resetPending()
    sawPrimaryButtons = false

    if (!(event instanceof TouchEvent)) {
      try {
        popup.releasePointerCapture(event.pointerId)
      } catch {}
    }

    const endTime = validTime(event.timeStamp)
    const duration =
      startTime !== null && endTime !== null && endTime > startTime ? endTime - startTime : 0
    const velocityDuration = duration > 0 ? Math.max(duration, MIN_VELOCITY_DURATION_MS) : 0
    const velocityX = velocityDuration > 0 ? offset.x / velocityDuration : 0
    const velocityY = velocityDuration > 0 ? offset.y / velocityDuration : 0
    let releaseVelocityX = lastVelocity.x
    let releaseVelocityY = lastVelocity.y

    if (lastSample && endTime !== null && endTime >= lastSample.time) {
      const age = endTime - lastSample.time

      if (age <= MAX_RELEASE_VELOCITY_AGE_MS) {
        const sampleDuration = Math.max(age, MIN_RELEASE_VELOCITY_DURATION_MS)
        const fromSampleX = offset.x - lastSample.x
        const fromSampleY = offset.y - lastSample.y

        if (Math.abs(fromSampleX) >= MIN_VELOCITY_SAMPLE_DISTANCE) {
          releaseVelocityX = fromSampleX / sampleDuration
        }

        if (Math.abs(fromSampleY) >= MIN_VELOCITY_SAMPLE_DISTANCE) {
          releaseVelocityY = fromSampleY / sampleDuration
        }
      } else {
        // Held still before letting go.
        releaseVelocityX = 0
        releaseVelocityY = 0
      }
    }

    const decision = options.onRelease({
      event,
      direction: intended,
      deltaX: offset.x,
      deltaY: offset.y,
      velocityX,
      velocityY,
      releaseVelocityX,
      releaseVelocityY,
    })

    if (cancelled && decision === undefined) {
      options.onSettle()
      return
    }

    let dismissDirection: SwipeDirection | undefined

    if (decision !== undefined) {
      dismissDirection = decision ? (intended ?? primaryDirection()) : undefined
    } else {
      dismissDirection = directions().find(
        (direction) => getDisplacement(direction, offset.x, offset.y) > DEFAULT_SWIPE_THRESHOLD,
      )
    }

    if (dismissDirection) {
      options.onDismiss(event, dismissDirection)
    } else {
      options.onSettle()
    }
  }

  function cancel(event: PointerEvent) {
    resetPending()

    if (!swiping) {
      return
    }

    swiping = false
    sawPrimaryButtons = false

    try {
      popup.releasePointerCapture(event.pointerId)
    } catch {}

    options.onSettle()
  }

  function handleMove(event: PointerEvent | TouchEvent, position: Point) {
    let endAfterMove = false

    if (!(event instanceof TouchEvent)) {
      const primary = event.buttons % 2 === 1

      if (primary) {
        sawPrimaryButtons = true
      }

      // Another button took over, as a right-click in the middle of a drag.
      if (event.buttons !== 0 && !primary) {
        cancel(event)
        return
      }

      // The button is up already: a fast flick on a trackpad sends this move just before
      // the pointerup, if one comes at all. It is the release.
      if (event.buttons === 0 && sawPrimaryButtons) {
        if (!swiping) {
          handleEnd(event)
          return
        }

        endAfterMove = true
      }
    }

    if (!swiping && pending) {
      const touchLike = event instanceof TouchEvent || event.pointerType === 'touch'

      if (!touchLike && event.defaultPrevented) {
        resetPending()
        return
      }

      if (canStart(position, event)) {
        let ignoreScrollable = false

        if (touchLike && pendingStart) {
          const target =
            elementAt(popup.getRootNode(), position.x, position.y) ?? getEventTarget(event)
          const scrollable = findGestureScrollable(target, document.body)

          if (scrollable && (popup.contains(scrollable) || scrollable.contains(popup))) {
            const edge = canSwipeFromScrollEdgeOnPendingMove(
              scrollable,
              position.x - pendingStart.x,
              position.y - pendingStart.y,
            )

            if (edge === false) {
              return
            }

            ignoreScrollable = edge === true
          }
        }

        const pendingFrom = pendingStart

        if (startAt(event, position, { ignoreScrollable })) {
          pending = false
          pendingStart = null

          if (pendingFrom && ignoreScrollable) {
            // Counted from the touchstart, so a quick flick off a scroller's edge closes.
            start = pendingFrom
            cancelBaseline = pendingFrom
            lastMove = pendingFrom
            firstMove = false
          } else {
            fromScrollable = false
          }
        }
      }
    }

    const previous = lastMove
    const movement = previous
      ? { x: position.x - previous.x, y: position.y - previous.y }
      : { x: 0, y: 0 }

    lastMove = position
    moveCore(event, position, movement)

    if (endAfterMove) {
      handleEnd(event)
    }
  }

  function canSwipeFromScrollEdgeOnPendingMove(
    scroller: HTMLElement,
    deltaX: number,
    deltaY: number,
  ) {
    const onAxis = (
      delta: number,
      offset: number,
      max: number,
      towardStart: boolean,
      towardEnd: boolean,
    ) =>
      (delta > 0 && offset <= 0 && towardStart) ||
      (delta < 0 && offset >= Math.max(0, max) && towardEnd)

    if ((allows('up') || allows('down')) && deltaY !== 0) {
      return onAxis(
        deltaY,
        scroller.scrollTop,
        scroller.scrollHeight - scroller.clientHeight,
        allows('down'),
        allows('up'),
      )
    }

    if ((allows('left') || allows('right')) && deltaX !== 0) {
      return onAxis(
        deltaX,
        scroller.scrollLeft,
        scroller.scrollWidth - scroller.clientWidth,
        allows('right'),
        allows('left'),
      )
    }

    return null
  }

  /**
   * Whether a touchmove is left to a gesture across the drawer: one already going that
   * way, or one that hasn't gone far enough either way to tell. iOS gives up a native
   * scroll for the whole gesture once its first cancelable move is prevented.
   */
  function yieldsToCrossAxis(state: TouchState, event: TouchEvent, touch: Touch) {
    if (state.preserveNativeCrossAxisScroll) {
      return true
    }

    if (
      state.drawerAxisAttributed ||
      state.allowSwipe === true ||
      !state.hasCrossAxisGestureTarget
    ) {
      return false
    }

    // Not cancelable: the browser has already given it to a native scroll.
    if (!event.cancelable) {
      state.preserveNativeCrossAxisScroll = true
      return true
    }

    const vertical = axis() === 'vertical'
    const along = Math.abs(vertical ? touch.clientY - state.start.y : touch.clientX - state.start.x)
    const across = Math.abs(
      vertical ? touch.clientX - state.start.x : touch.clientY - state.start.y,
    )

    if (across >= AXIS_LOCK_SLOP && across > along + AXIS_LOCK_BIAS) {
      state.preserveNativeCrossAxisScroll = true
      return true
    }

    if (along >= AXIS_LOCK_SLOP) {
      state.drawerAxisAttributed = true
      return false
    }

    return true
  }

  function onDocumentTouchMove(event: TouchEvent) {
    if (ignoreTouch || !options.isEnabled()) {
      return
    }

    const state = touchState
    const touch = event.touches[0]

    if (!touch || !state) {
      return
    }

    processTouchMove(event, state, touch)
    state.last = { x: touch.clientX, y: touch.clientY }
  }

  function processTouchMove(event: TouchEvent, state: TouchState, touch: Touch) {
    const vertical = axis() === 'vertical'
    const delta = vertical ? touch.clientY - state.last.y : touch.clientX - state.last.x
    const position = { x: touch.clientX, y: touch.clientY }

    // Pinching, or adjusting a text selection.
    if (event.touches.length === 2 || hasSelectionWithin(root)) {
      return
    }

    if (yieldsToCrossAxis(state, event, touch)) {
      return
    }

    const scroller = state.scrollTarget

    if (!scroller || scroller === document.documentElement || scroller === document.body) {
      if (event.cancelable) {
        event.preventDefault()
      }

      event.stopPropagation()
      handleMove(event, position)
      return
    }

    if (!isScrollable(scroller, axis())) {
      // Nothing to scroll there: not the page behind either.
      if (event.cancelable) {
        event.preventDefault()
      }

      event.stopPropagation()
      return
    }

    if (delta !== 0) {
      const direction = primaryDirection() ?? 'down'
      const towardClose = dismissesFromStartEdge(direction, axis()) ? delta > 0 : delta < 0
      const fromEdge = towardClose && isAtSwipeStartEdge(scroller, axis(), direction)

      if (!state.allowSwipe) {
        if (event.cancelable && fromEdge) {
          state.allowSwipe = true
          event.preventDefault()
        } else {
          state.allowSwipe = false
        }
      } else if (event.cancelable) {
        event.preventDefault()
      }
    }

    if (state.allowSwipe === true) {
      event.stopPropagation()
      handleMove(event, position)
    }
  }

  root.addEventListener(
    'pointerdown',
    (event) => {
      lastPointerType = event.pointerType
      ignoreTouchStartFromPen = event.pointerType === 'pen'

      if (!options.isEnabled() || event.pointerType === 'touch') {
        return
      }

      // A mouse or a pen selects text in the content, and leaves alone what asks to be.
      const atPoint = elementAt(root.getRootNode(), event.clientX, event.clientY)

      if (atPoint?.closest(`${SWIPE_IGNORE},${CONTENT}`)) {
        return
      }

      handleStart(event, { x: event.clientX, y: event.clientY })
    },
    { signal },
  )
  root.addEventListener(
    'pointermove',
    (event) => {
      if (event.pointerType !== 'touch') {
        handleMove(event, { x: event.clientX, y: event.clientY })
      }
    },
    { signal },
  )

  for (const type of ['pointerup', 'pointercancel'] as const) {
    root.addEventListener(
      type,
      (event) => {
        lastPointerType = ''

        if (event.pointerType !== 'touch') {
          handleEnd(event)
        }
      },
      { signal },
    )
  }

  root.addEventListener(
    'touchstart',
    (event) => {
      if (lastPointerType === 'pen' && ignoreTouchStartFromPen) {
        ignoreTouchStartFromPen = false
        ignoreTouch = false
        touchState = null
        return
      }

      const touch = event.touches[0]

      if (!options.isEnabled() || !touch) {
        ignoreTouch = false
        touchState = null
        return
      }

      const target = getEventTarget(event) ?? root

      if (
        !root.contains(target) ||
        event
          .composedPath()
          .some((node) => node instanceof HTMLInputElement && node.type === 'range')
      ) {
        ignoreTouch = true
        touchState = null
        return
      }

      const atPoint = elementAt(root.getRootNode(), touch.clientX, touch.clientY)
      const crossValue = crossAxis() === 'horizontal' ? 'x' : 'y'

      // `data-swipe-ignore="x"` on a vertical drawer hands sideways drags to the element;
      // any other value leaves the element alone entirely.
      if (atPoint?.closest(`${SWIPE_IGNORE}:not([data-swipe-ignore="${crossValue}"])`)) {
        ignoreTouch = true
        touchState = null
        return
      }

      ignoreTouch = false
      const scrollTarget = findScrollableTouchTarget(target, root, axis())
      const hasCrossAxisGestureTarget =
        findScrollableTouchTarget(target, root, crossAxis()) != null ||
        atPoint?.closest(`[data-swipe-ignore="${crossValue}"]`) != null
      const direction = primaryDirection() ?? 'down'

      touchState = {
        start: { x: touch.clientX, y: touch.clientY },
        last: { x: touch.clientX, y: touch.clientY },
        startedInPopup: Boolean(atPoint && popup.contains(atPoint)),
        scrollTarget,
        hasCrossAxisGestureTarget,
        allowSwipe:
          scrollTarget && !isAtSwipeStartEdge(scrollTarget, axis(), direction) ? false : null,
        preserveNativeCrossAxisScroll: false,
        drawerAxisAttributed: false,
      }

      handleStart(event, { x: touch.clientX, y: touch.clientY })
    },
    { passive: true, signal },
  )

  for (const type of ['touchend', 'touchcancel'] as const) {
    root.addEventListener(
      type,
      (event) => {
        ignoreTouch = false
        touchState = null
        lastPointerType = ''
        ignoreTouchStartFromPen = false
        handleEnd(event)
      },
      { signal },
    )
  }

  let touchMoves: AbortController | undefined

  return {
    get swiping() {
      return swiping
    },
    /**
     * Hears every touchmove before anything else does, so a drag can keep it from scrolling
     * the page; only while the drawer is open, as it holds every scroll of the page up.
     */
    listen(on: boolean) {
      touchMoves?.abort()
      touchMoves = undefined

      if (on && !signal.aborted) {
        touchMoves = new AbortController()
        document.addEventListener('touchmove', onDocumentTouchMove, {
          capture: true,
          passive: false,
          signal: AbortSignal.any([touchMoves.signal, signal]),
        })
      }
    },
    /** Lets go of a drag in progress, as when the drawer closes another way. */
    reset() {
      swiping = false
      resetPending()
      touchState = null
      offset = { x: 0, y: 0 }
    },
  }
}
