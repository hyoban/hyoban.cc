import { createDrawer, createScrollArea, type Drawer, type ScrollArea } from '@hyoban/drawer'

// The map's sheets (a place, the lists, a trip, the settings) as drawers: beside the map
// on a wide screen, swiped right to close, and along a phone's bottom, swiped down. The map
// stays in reach behind them and has its own ways to close them, Escape included, so the
// drawer only comes in, goes, and hands a swipe to `onSwipeClose`, which closes the sheet
// the way its close button does. It goes on closing itself all the same, as an address
// going back closes the sheet only once the browser gets there.

export type Sheet = Drawer

// A mouse or a trackpad, rather than a finger.
const FINE_POINTER = window.matchMedia('(hover: hover) and (pointer: fine)')

export function createSheet(
  element: HTMLElement,
  {
    modal = false,
    narrow,
    onClosed,
    onSwipeClose,
    parent,
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
    parent: () => {
      const under = parent?.()

      return under?.isOpen && under !== sheet ? under : undefined
    },
    popup: element,
    signal,
    swipeDirection: getDirection(),
  })

  // What scrolls in it gets the drawer's thin scrollbar, drawn over its edge in the sheet,
  // where a mouse scrolls it beside the map; a phone keeps its own.
  const content = element.querySelector<HTMLElement>('[data-drawer-content]')
  let scrollArea: ScrollArea | undefined

  function syncScrollArea() {
    const wanted = Boolean(content) && !narrow.matches && FINE_POINTER.matches

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
      syncScrollArea()
    },
    { signal },
  )
  FINE_POINTER.addEventListener('change', syncScrollArea, { signal })

  return sheet
}
