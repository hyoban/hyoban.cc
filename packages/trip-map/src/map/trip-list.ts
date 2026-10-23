import { createSheet } from './sheet.ts'
// The list of every trip, beside the map where a place's drawer sits, while the address
// is the list's own (`/trip/`): its openers and its close button ask for the address to
// change, and the map shows or closes the list as it follows it.
//
// Its rows are the page's trip links: clicking one opens the trip's sidebar, which plays
// it (see trip-details.ts). Resting the pointer on a row a while, or reaching it by
// keyboard, flies the map to its route and lifts it meanwhile. Only after a while, so a
// pointer passing over the rows on its way doesn't light them all; meanwhile the row
// fills a line along its foot (see styles.css), saying it will.

// How long the pointer rests on a row before its route is lifted.
const DWELL = 2000

export type TripList = ReturnType<typeof createTripList>

export function createTripList({
  closeButton,
  narrow,
  list,
  onClose,
  onHighlight,
  onOpen,
  onRequestClose,
  onRequestOpen,
  openers,
  signal,
}: {
  closeButton: HTMLButtonElement | null
  /** Whether the screen is a phone's, where the list comes up from the bottom. */
  narrow: MediaQueryList
  list: HTMLElement
  /** The list started closing; `restoreView` unless a trip is taking the map. */
  onClose: (options: { restoreView: boolean }) => void
  /** The trip to lift on the map, or none; `frame` when the map should fly to it. */
  onHighlight: (id: string | undefined, options: { frame: boolean }) => void
  /** A trip's row was clicked, to open its sidebar. */
  onOpen: (id: string) => void
  /** The list's close button, or an opener while it's open, was pressed. */
  onRequestClose: () => void
  /** An opener was pressed while the list was closed. */
  onRequestOpen: () => void
  openers: readonly HTMLElement[]
  signal: AbortSignal
}) {
  let trigger: HTMLElement | undefined
  let dwell: ReturnType<typeof setTimeout> | undefined
  // The row filling up, and the row lifted meanwhile.
  let dwelling: HTMLElement | undefined
  let previewed: HTMLElement | undefined
  // The row whose route the map shows.
  let lifted: HTMLElement | undefined

  list.style.setProperty('--trip-map-dwell', `${DWELL}ms`)

  let shown = !list.hidden
  // Swiped away, it closes as its close button does.
  const sheet = createSheet(list, { narrow, onSwipeClose: onRequestClose, signal })

  function isOpen() {
    return shown
  }

  function getId(link: HTMLElement) {
    return link.dataset.tripMapTripLink!
  }

  function setDwelling(link: HTMLElement | undefined) {
    clearTimeout(dwell)
    dwell = undefined
    delete dwelling?.dataset.tripMapDwelling
    dwelling = link

    if (link) {
      link.dataset.tripMapDwelling = ''
      dwell = setTimeout(() => setPreviewed(link), DWELL)
    }
  }

  /** Lifts what's previewed, flying to it if it's new. */
  function update(options: { frame: boolean }) {
    const next = previewed

    if (next === lifted) {
      return
    }

    delete lifted?.dataset.tripMapLifted
    lifted = next

    if (next) {
      next.dataset.tripMapLifted = ''
    }

    onHighlight(next && getId(next), options)
  }

  function setPreviewed(link: HTMLElement | undefined) {
    if (link === previewed) {
      return
    }

    setDwelling(undefined)
    previewed = link
    update({ frame: Boolean(link) })
  }

  function setExpanded(expanded: boolean) {
    for (const opener of openers) {
      opener.ariaExpanded = String(expanded)
    }
  }

  function show(options: { focus?: boolean } = {}) {
    if (isOpen()) {
      return
    }

    shown = true
    sheet.open()
    setExpanded(true)

    if (options.focus !== false) {
      ;(closeButton ?? list).focus({ preventScroll: true })
    }
  }

  function close(options: { restoreFocus?: boolean; restoreView?: boolean } = {}) {
    if (!isOpen()) {
      // Rendered open by the page, before the map knew of it.
      setExpanded(false)
      return
    }

    setPreviewed(undefined)
    setExpanded(false)

    shown = false
    sheet.close()
    onClose({ restoreView: options.restoreView !== false })

    if (options.restoreFocus !== false && trigger?.isConnected) {
      trigger.focus({ preventScroll: true })
    }
  }

  function getLink(target: EventTarget | null) {
    return target instanceof Element
      ? (target.closest<HTMLElement>('[data-trip-map-trip-link]') ?? undefined)
      : undefined
  }

  /** Whether a pointer event crossed a row's edge, rather than moving within it. */
  function crossesRow(event: PointerEvent) {
    const link = getLink(event.target)

    return event.pointerType === 'mouse' && link && getLink(event.relatedTarget) !== link
      ? link
      : undefined
  }

  for (const opener of openers) {
    opener.ariaExpanded = String(!list.hidden)
    opener.addEventListener(
      'click',
      (event) => {
        event.preventDefault()

        if (isOpen()) {
          onRequestClose()
        } else {
          trigger = opener
          onRequestOpen()
        }
      },
      { signal },
    )
  }

  closeButton?.addEventListener('click', onRequestClose, { signal })
  list.addEventListener(
    'click',
    (event) => {
      const link = getLink(event.target)

      if (link) {
        event.preventDefault()
        setPreviewed(undefined)
        onOpen(getId(link))
      }
    },
    { signal },
  )
  list.addEventListener(
    'focusin',
    (event) => {
      const link = getLink(event.target)

      // Reached by keyboard: a pointer's focus comes with its click.
      if (link && link.matches(':focus-visible')) {
        setPreviewed(link)
      }
    },
    { signal },
  )
  list.addEventListener(
    'focusout',
    (event) => {
      if (getLink(event.target) === previewed) {
        setPreviewed(undefined)
      }
    },
    { signal },
  )
  list.addEventListener(
    'pointerover',
    (event) => {
      const link = crossesRow(event)

      if (link && link !== lifted) {
        setDwelling(link)
      }
    },
    { signal },
  )
  list.addEventListener(
    'pointerout',
    (event) => {
      const link = crossesRow(event)

      // Leaving a row lets go of it: its wait, or its route lifted meanwhile.
      if (link && link === dwelling) {
        setDwelling(undefined)
      }

      if (link && link === previewed) {
        setPreviewed(undefined)
      }
    },
    { signal },
  )
  signal.addEventListener('abort', () => clearTimeout(dwell))

  return {
    close,
    get isOpen() {
      return isOpen()
    },
    /** The drawer it is, for one to open over it. */
    sheet,
    show,
  }
}
