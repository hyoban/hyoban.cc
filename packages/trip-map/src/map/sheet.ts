import { createDrawer, createScrollArea, type Drawer, type ScrollArea } from '@hyoban/drawer'

// The map's sheets (a place, the lists, a trip, the settings) as drawers: beside the map
// on a wide screen, swiped right to close, and along a phone's bottom, swiped down. The map
// stays in reach behind them and has its own ways to close them, Escape included, so the
// drawer only comes in, goes, and hands a swipe to `onSwipeClose`, which closes the sheet
// the way its close button does. It goes on closing itself all the same, as an address
// going back closes the sheet only once the browser gets there.
//
// On a phone a sheet given `phoneHeight` rests at that height and can be pulled up to the
// top of the screen and back (two snap points): it's as tall as the screen all along, the
// part below the resting height out of sight, and its content padded by that part so
// what's at its end can be scrolled into view without pulling it up.

export type Sheet = Drawer

export function createSheet(
  element: HTMLElement,
  {
    modal = false,
    narrow,
    onClosed,
    onSwipeClose,
    parent,
    phoneHeight,
    signal,
  }: {
    /** For the settings, which hold the map out of reach while open. */
    modal?: boolean
    /** Whether the screen is a phone's, where the sheets come up from the bottom. */
    narrow: MediaQueryList
    /** Gone, after closing: what it held can be let go of. */
    onClosed?: () => void
    onSwipeClose?: () => void
    /** The sheet it opens over, which steps back behind it. */
    parent?: () => Sheet | undefined
    /**
     * Where it rests on a phone, as a share of the screen's height up to `max` rems (as the
     * site's own CSS has it), pulled up from there to the whole screen.
     */
    phoneHeight?: { share: number; max: number }
    signal: AbortSignal
  },
) {
  const getDirection = () => (narrow.matches ? 'down' : 'right')
  const sheet = createDrawer({
    defaultOpen: element instanceof HTMLDialogElement ? element.open : !element.hidden,
    disableEscapeDismissal: !modal,
    disablePointerDismissal: !modal,
    finalFocus: false,
    initialFocus: false,
    modal,
    onOpenChange: (open, details) => {
      if (!open && details.reason === 'swipe') {
        onSwipeClose?.()
      }
    },
    onOpenChangeComplete: (open) => {
      if (!open) {
        onClosed?.()
      }
    },
    onSnapPointChange: (point) => setHidden(point),
    parent: () => {
      const under = parent?.()

      return under?.isOpen && under !== sheet ? under : undefined
    },
    popup: element,
    signal,
    // A flick goes one point at a time: down from the whole screen to resting, then closed.
    snapToSequentialPoints: true,
    swipeDirection: getDirection(),
  })

  // What scrolls in it gets the drawer's thin scrollbar, drawn over its edge in the sheet,
  // where a mouse scrolls it beside the map; a phone keeps its own.
  const content = element.querySelector<HTMLElement>('[data-drawer-content]')

  function getRestingHeight() {
    if (!phoneHeight) {
      return 0
    }

    const rem = Number.parseFloat(getComputedStyle(document.documentElement).fontSize) || 16

    return Math.round(Math.min(window.innerHeight * phoneHeight.share, phoneHeight.max * rem))
  }

  /** The content's padding for what of the sheet is out of sight at `point`. */
  function setHidden(point: number | string | null) {
    const hidden =
      phoneHeight && narrow.matches && point !== 1
        ? Math.max(0, element.offsetHeight - getRestingHeight())
        : 0

    content?.style.setProperty('--sheet-hidden', `${hidden}px`)
  }

  function syncSnapPoints() {
    if (!phoneHeight) {
      return
    }

    // Pulled up, it stays pulled up as the screen changes; resting, it rests anew.
    const expanded = sheet.snapPoint === 1
    sheet.setSnapPoints(narrow.matches ? [getRestingHeight(), 1] : undefined)

    if (expanded && narrow.matches) {
      sheet.setSnapPoint(1)
    }

    setHidden(sheet.snapPoint)
  }

  if (phoneHeight) {
    syncSnapPoints()
    window.addEventListener('resize', syncSnapPoints, { signal })

    if (typeof ResizeObserver === 'function') {
      // Its own height comes with the attribute the snap points set.
      const resizes = new ResizeObserver(() => setHidden(sheet.snapPoint))
      resizes.observe(element)
      signal.addEventListener('abort', () => resizes.disconnect())
    }
  }

  // A mouse or a trackpad, rather than a finger.
  const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)')
  let scrollArea: ScrollArea | undefined

  function syncScrollArea() {
    const wanted = Boolean(content) && !narrow.matches && finePointer.matches

    if (wanted && !scrollArea && content) {
      scrollArea = createScrollArea({ root: element, signal, viewport: content })
    } else if (!wanted && scrollArea) {
      scrollArea.destroy()
      scrollArea = undefined
    }
  }

  syncScrollArea()
  narrow.addEventListener(
    'change',
    () => {
      sheet.setSwipeDirection(getDirection())
      syncSnapPoints()
      syncScrollArea()
    },
    { signal },
  )
  finePointer.addEventListener('change', syncScrollArea, { signal })

  return sheet
}
