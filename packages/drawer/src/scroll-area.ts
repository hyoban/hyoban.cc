/**
 * @license MIT
 * Ported from Base UI scroll-area/root/ScrollAreaRoot, scroll-area/viewport/ScrollAreaViewport,
 * scroll-area/scrollbar/ScrollAreaScrollbar and scroll-area/thumb/ScrollAreaThumb
 * (@base-ui/react 1.9.0, https://github.com/mui/base-ui), Copyright (c) 2019 Material-UI SAS.
 * Changes Copyright (c) 2026 Stephen Zhou. See LICENSE and THIRD-PARTY-NOTICES.md.
 */

// Base UI's ScrollArea on plain DOM: an element that already scrolls keeps scrolling as it
// does, its own scrollbar hidden, and a scrollbar drawn over its edge in `root` instead,
// one for each way it scrolls. Its thumb is dragged, a press on its track jumps there, and
// a wheel over it scrolls. The state goes on the elements as Base UI's does:
//
// - `data-has-overflow-x/y`, `data-scrolling` while scrolled, on the root;
// - `data-orientation`, `data-hovering` and `data-scrolling` on each scrollbar;
// - `--scroll-area-thumb-height/width` on the scrollbar, and
//   `--scroll-area-overflow-y-start/end` (and x) on the viewport: how far it can scroll.

export type ScrollAreaOrientation = 'vertical' | 'horizontal'

export type ScrollAreaOptions = {
  /** The element that scrolls. */
  viewport: HTMLElement
  /** Where the scrollbars are drawn, the viewport's parent by default; positioned if it isn't. */
  root?: HTMLElement | null | undefined
  /** The ways it gets a scrollbar: vertical by default. */
  orientations?: readonly ScrollAreaOrientation[] | undefined
  signal?: AbortSignal | undefined
}

export type ScrollArea = {
  /** Measures again, as after the content changed in a way nothing observed. */
  update(): void
  /** Takes the scrollbars away, the viewport's own back. */
  destroy(): void
}

const SCROLL_TIMEOUT = 500
const MIN_THUMB_SIZE = 16

type Axis = {
  orientation: ScrollAreaOrientation
  scrollbar: HTMLElement
  thumb: HTMLElement
}

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max)

/** The padding (or margin) along an axis, both sides. */
function getOffset(element: HTMLElement, box: 'padding' | 'margin', vertical: boolean) {
  const style = getComputedStyle(element)
  const [start, end] = vertical ? ['Top', 'Bottom'] : ['Left', 'Right']

  return (
    Number.parseFloat(style[`${box}${start}` as 'paddingTop']) +
    Number.parseFloat(style[`${box}${end}` as 'paddingBottom'])
  )
}

/**
 * Where `element` sits inside `root`'s border, in `root`'s own pixels: a transform on the
 * root, as a drawer stepped back under another has, scales both alike and is divided out.
 */
function getLayoutOffset(element: HTMLElement, root: HTMLElement) {
  const outer = root.getBoundingClientRect()
  const inner = element.getBoundingClientRect()
  const scaleX = root.offsetWidth ? outer.width / root.offsetWidth : 1
  const scaleY = root.offsetHeight ? outer.height / root.offsetHeight : 1

  return {
    left: (inner.left - outer.left) / (scaleX || 1) - root.clientLeft,
    top: (inner.top - outer.top) / (scaleY || 1) - root.clientTop,
  }
}

/**
 * The inline style of `element`. Read through HTMLElement's own getter, as a form's
 * `style` is its control named so, if it has one (the map's settings choose a style).
 */
function getInlineStyle(element: HTMLElement): CSSStyleDeclaration {
  // Looked up here rather than as the module loads, which may be where there's no DOM.
  const styleProperty = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'style')

  return (styleProperty?.get?.call(element) ?? element.style) as CSSStyleDeclaration
}

export function createScrollArea(options: ScrollAreaOptions): ScrollArea {
  const { viewport } = options
  const root = options.root ?? viewport.parentElement ?? viewport
  const orientations = options.orientations ?? ['vertical']
  const controller = new AbortController()
  const { signal } = controller
  const viewportStyle = getInlineStyle(viewport)
  const rootStyle = getInlineStyle(root)

  options.signal?.addEventListener('abort', () => destroy(), { once: true })

  const scrollingTimers = new Map<ScrollAreaOrientation, ReturnType<typeof setTimeout>>()
  let lastScroll = { x: viewport.scrollLeft, y: viewport.scrollTop }
  let drag:
    | {
        pointerId: number
        axis: Axis
        start: number
        startScroll: number
        snapType: string
      }
    | undefined
  const rootWasStatic = !['relative', 'absolute', 'fixed', 'sticky'].includes(
    getComputedStyle(root).position,
  )

  if (rootWasStatic) {
    rootStyle.position = 'relative'
  }

  root.setAttribute('data-scroll-area-root', '')
  viewport.setAttribute('data-scroll-area-viewport', '')

  const axes: Axis[] = orientations.map((orientation) => {
    const scrollbar = document.createElement('div')
    const thumb = document.createElement('div')

    scrollbar.setAttribute('data-scroll-area-scrollbar', '')
    scrollbar.dataset.orientation = orientation
    // A drawer's drag leaves the scrollbar to itself.
    scrollbar.setAttribute('data-swipe-ignore', '')
    scrollbar.setAttribute('aria-hidden', 'true')
    scrollbar.hidden = true
    thumb.setAttribute('data-scroll-area-thumb', '')
    scrollbar.append(thumb)
    root.append(scrollbar)

    return { orientation, scrollbar, thumb }
  })

  function setScrolling(axis: ScrollAreaOrientation, scrolling: boolean) {
    const scrollbar = axes.find((each) => each.orientation === axis)?.scrollbar
    scrollbar?.toggleAttribute('data-scrolling', scrolling)
    root.toggleAttribute(
      'data-scrolling',
      axes.some((each) => each.scrollbar.hasAttribute('data-scrolling')),
    )
  }

  function startScrolling(axis: ScrollAreaOrientation) {
    setScrolling(axis, true)
    clearTimeout(scrollingTimers.get(axis))
    scrollingTimers.set(
      axis,
      setTimeout(() => setScrolling(axis, false), SCROLL_TIMEOUT),
    )
  }

  /**
   * Sizes the thumb and returns where it goes. Pulled past an end (Safari's rubber band), it
   * shrinks against that end, as a native scrollbar does.
   */
  function placeThumb(
    thumb: HTMLElement,
    sizeVar: string,
    scrolled: number,
    maxScroll: number,
    content: number,
    size: number,
    maxThumbOffset: number,
  ) {
    const clamped = clamp(scrolled, 0, maxScroll)
    const overscroll = scrolled - clamped
    const nextSize = Math.max(MIN_THUMB_SIZE, (size * content) / (content + Math.abs(overscroll)))

    if (overscroll) {
      thumb.style.setProperty(sizeVar, `${nextSize}px`)
    } else {
      thumb.style.removeProperty(sizeVar)
    }

    const offset = maxScroll ? (clamped / maxScroll) * maxThumbOffset : 0

    return offset + (overscroll > 0 ? size - nextSize : 0)
  }

  function update() {
    if (signal.aborted) {
      return
    }

    const { clientHeight, clientWidth, scrollHeight, scrollLeft, scrollTop, scrollWidth } = viewport
    const hiddenY = clientHeight >= scrollHeight
    const hiddenX = clientWidth >= scrollWidth
    const maxTop = Math.max(0, scrollHeight - clientHeight)
    const maxLeft = Math.max(0, scrollWidth - clientWidth)
    const { left, top } = getLayoutOffset(viewport, root)
    const right = root.clientWidth - (left + viewport.offsetWidth)
    const bottom = root.clientHeight - (top + viewport.offsetHeight)

    root.toggleAttribute('data-has-overflow-y', !hiddenY)
    root.toggleAttribute('data-has-overflow-x', !hiddenX)

    const edges = {
      '--scroll-area-overflow-y-start': hiddenY ? 0 : clamp(scrollTop, 0, maxTop),
      '--scroll-area-overflow-y-end': hiddenY ? 0 : maxTop - clamp(scrollTop, 0, maxTop),
      '--scroll-area-overflow-x-start': hiddenX ? 0 : clamp(scrollLeft, 0, maxLeft),
      '--scroll-area-overflow-x-end': hiddenX ? 0 : maxLeft - clamp(scrollLeft, 0, maxLeft),
    }

    for (const [name, value] of Object.entries(edges)) {
      viewportStyle.setProperty(name, `${value}px`)
    }

    for (const { orientation, scrollbar, thumb } of axes) {
      const vertical = orientation === 'vertical'
      const hidden = vertical ? hiddenY : hiddenX

      scrollbar.hidden = hidden

      if (hidden) {
        continue
      }

      // Over the viewport's edge, as long as it is.
      if (vertical) {
        scrollbar.style.top = `${top}px`
        scrollbar.style.height = `${viewport.clientHeight}px`
        scrollbar.style.right = `${right}px`
      } else {
        scrollbar.style.left = `${left}px`
        scrollbar.style.width = `${viewport.clientWidth}px`
        scrollbar.style.bottom = `${bottom}px`
      }

      const track = vertical ? viewport.clientHeight : viewport.clientWidth
      const content = vertical ? scrollHeight : scrollWidth
      const inner =
        track - getOffset(scrollbar, 'padding', vertical) - getOffset(thumb, 'margin', vertical)
      const size = Math.max(MIN_THUMB_SIZE, inner * (track / content))
      const maxThumbOffset = inner - size
      const sizeVar = vertical ? '--scroll-area-thumb-height' : '--scroll-area-thumb-width'

      scrollbar.style.setProperty(sizeVar, `${size}px`)

      const offset = placeThumb(
        thumb,
        sizeVar,
        vertical ? scrollTop : scrollLeft,
        vertical ? maxTop : maxLeft,
        content,
        size,
        maxThumbOffset,
      )

      thumb.style.transform = vertical
        ? `translate3d(0,${offset}px,0)`
        : `translate3d(${offset}px,0,0)`
    }
  }

  // Scrolling snaps to its points, which would make a dragged thumb jump between them;
  // a native scrollbar doesn't snap while dragged either.
  function startDrag(event: PointerEvent, axis: Axis) {
    const vertical = axis.orientation === 'vertical'

    drag = {
      pointerId: event.pointerId,
      axis,
      start: vertical ? event.clientY : event.clientX,
      startScroll: vertical ? viewport.scrollTop : viewport.scrollLeft,
      snapType: drag?.snapType ?? viewportStyle.scrollSnapType,
    }
    viewportStyle.scrollSnapType = 'none'
    axis.thumb.setPointerCapture(event.pointerId)
  }

  function endDrag(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.pointerId) {
      return
    }

    const { axis, snapType } = drag
    drag = undefined
    viewportStyle.scrollSnapType = snapType
    setScrolling(axis.orientation, false)

    if (axis.thumb.hasPointerCapture(event.pointerId)) {
      axis.thumb.releasePointerCapture(event.pointerId)
    }
  }

  function moveDrag(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.pointerId) {
      return
    }

    // The button let go without a release reaching the thumb.
    if (event.buttons % 2 === 0) {
      endDrag(event)
      return
    }

    const { axis } = drag
    const vertical = axis.orientation === 'vertical'
    const thumbSize = vertical ? axis.thumb.offsetHeight : axis.thumb.offsetWidth
    const track = vertical ? axis.scrollbar.offsetHeight : axis.scrollbar.offsetWidth
    const maxThumbOffset =
      track -
      thumbSize -
      getOffset(axis.scrollbar, 'padding', vertical) -
      getOffset(axis.thumb, 'margin', vertical)
    const delta = (vertical ? event.clientY : event.clientX) - drag.start
    const ratio = maxThumbOffset <= 0 ? 0 : delta / maxThumbOffset
    const scrollable = vertical
      ? viewport.scrollHeight - viewport.clientHeight
      : viewport.scrollWidth - viewport.clientWidth
    const next = drag.startScroll + ratio * scrollable

    if (vertical) {
      viewport.scrollTop = next
    } else {
      viewport.scrollLeft = next
    }

    event.preventDefault()
    startScrolling(axis.orientation)
  }

  for (const axis of axes) {
    const { orientation, scrollbar, thumb } = axis
    const vertical = orientation === 'vertical'

    // A press on the track puts the thumb's middle there, and drags it on from there.
    scrollbar.addEventListener(
      'pointerdown',
      (event) => {
        if (event.button !== 0) {
          return
        }

        if (!thumb.contains(event.target as Node)) {
          const thumbSize = vertical ? thumb.offsetHeight : thumb.offsetWidth
          const padding = getOffset(scrollbar, 'padding', vertical)
          const margin = getOffset(thumb, 'margin', vertical)
          const box = scrollbar.getBoundingClientRect()
          const track = vertical ? scrollbar.offsetHeight : scrollbar.offsetWidth
          const maxThumbOffset = track - thumbSize - padding - margin

          if (maxThumbOffset <= 0) {
            return
          }

          const position =
            (vertical ? event.clientY - box.top : event.clientX - box.left) -
            thumbSize / 2 -
            padding +
            margin / 2
          const scrollable = vertical
            ? viewport.scrollHeight - viewport.clientHeight
            : viewport.scrollWidth - viewport.clientWidth

          viewportStyle.scrollSnapType = 'none'

          if (vertical) {
            viewport.scrollTop = (position / maxThumbOffset) * scrollable
          } else {
            viewport.scrollLeft = (position / maxThumbOffset) * scrollable
          }

          startScrolling(orientation)
        }

        startDrag(event, axis)
      },
      { signal },
    )
    // Pressing a scrollbar never moves focus, as a native one doesn't.
    scrollbar.addEventListener('mousedown', (event) => event.preventDefault(), { signal })
    scrollbar.addEventListener('pointerup', endDrag, { signal })
    scrollbar.addEventListener('pointercancel', endDrag, { signal })
    thumb.addEventListener('pointermove', moveDrag, { signal })
    thumb.addEventListener('pointerup', endDrag, { signal })
    thumb.addEventListener('pointercancel', endDrag, { signal })

    // A wheel over the scrollbar scrolls the viewport, and the page once it's at an end.
    scrollbar.addEventListener(
      'wheel',
      (event) => {
        if (event.ctrlKey) {
          return
        }

        const delta = vertical ? event.deltaY : event.deltaX
        const max = vertical
          ? viewport.scrollHeight - viewport.clientHeight
          : viewport.scrollWidth - viewport.clientWidth
        const value = vertical ? viewport.scrollTop : viewport.scrollLeft

        if (delta === 0 || (value <= 0 && delta < 0) || (value >= max && delta > 0)) {
          return
        }

        event.preventDefault()

        if (vertical) {
          viewport.scrollTop = clamp(value + delta, 0, max)
        } else {
          viewport.scrollLeft = clamp(value + delta, 0, max)
        }
      },
      { passive: false, signal },
    )
  }

  viewport.addEventListener(
    'scroll',
    () => {
      update()

      const next = { x: viewport.scrollLeft, y: viewport.scrollTop }

      if (next.y !== lastScroll.y) {
        startScrolling('vertical')
      }

      if (next.x !== lastScroll.x) {
        startScrolling('horizontal')
      }

      lastScroll = next
    },
    { passive: true, signal },
  )

  // The scrollbars show they're there while a mouse is over the area.
  root.addEventListener(
    'pointerenter',
    (event) => {
      if (event.pointerType !== 'touch') {
        for (const { scrollbar } of axes) scrollbar.setAttribute('data-hovering', '')
      }
    },
    { signal },
  )
  root.addEventListener(
    'pointerleave',
    () => {
      for (const { scrollbar } of axes) scrollbar.removeAttribute('data-hovering')
    },
    { signal },
  )

  // Measured again as the viewport or what it holds changes size: a fragment fetched into
  // it, a photo loaded.
  if (typeof ResizeObserver === 'function') {
    const resizes = new ResizeObserver(() => update())
    const observeChildren = () => {
      resizes.disconnect()
      resizes.observe(viewport)
      resizes.observe(root)

      for (const child of viewport.children) {
        resizes.observe(child)
      }
    }
    const mutations = new MutationObserver(observeChildren)

    observeChildren()
    mutations.observe(viewport, { childList: true })
    signal.addEventListener('abort', () => {
      resizes.disconnect()
      mutations.disconnect()
    })
  }

  function destroy() {
    if (signal.aborted) {
      return
    }

    controller.abort()

    for (const timer of scrollingTimers.values()) {
      clearTimeout(timer)
    }

    for (const { scrollbar } of axes) {
      scrollbar.remove()
    }

    for (const name of [
      'data-scroll-area-root',
      'data-scrolling',
      'data-has-overflow-x',
      'data-has-overflow-y',
    ]) {
      root.removeAttribute(name)
    }

    viewport.removeAttribute('data-scroll-area-viewport')

    for (const name of Array.from(viewportStyle)) {
      if (name.startsWith('--scroll-area-')) {
        viewportStyle.removeProperty(name)
      }
    }

    if (rootWasStatic) {
      rootStyle.position = ''
    }
  }

  update()

  return { update, destroy }
}
