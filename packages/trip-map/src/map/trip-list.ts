// The list of every trip, beside the map where a place's drawer sits, while the address
// is the list's own (`/trip/`): its openers and its close button ask for the address to
// change, and the map shows or closes the list as it follows it.
//
// Its rows are the page's trip links, and a row is looked at on the map before it's
// played: clicking one picks it, the map flying to its route and lifting it, and shows
// its play button (`[data-trip-map-trip-play]` by the row); clicking it again, or the
// button, plays it. Resting the pointer on another row a while, or reaching it by
// keyboard, lifts that one meanwhile, and leaving it lifts the picked one again. A route
// picked on the map picks its row, and the other way round (see overview.ts). Only
// after a while, so a pointer passing over the rows on its way doesn't light them all;
// meanwhile the row fills a line along its foot (see styles.css), saying it will.

// How long the pointer rests on a row before its route is lifted.
const DWELL = 2000
// The list sliding away as it closes, like the drawer (see styles.css).
const EXIT = { duration: 200, easing: 'cubic-bezier(0.4, 0, 1, 1)' }

export type TripList = ReturnType<typeof createTripList>

export function createTripList({
  closeButton,
  isNarrow,
  list,
  onClose,
  onHighlight,
  onPick,
  onPlay,
  onRequestClose,
  onRequestOpen,
  openers,
  reducedMotion,
  signal,
}: {
  closeButton: HTMLButtonElement | null
  isNarrow: () => boolean
  list: HTMLElement
  /** The list started closing; `restoreView` unless a trip is taking the map. */
  onClose: (options: { restoreView: boolean }) => void
  /**
   * The trip to lift on the map, or none; `frame` when the map should fly to it,
   * rather than lift a picked trip again as the pointer leaves another.
   */
  onHighlight: (id: string | undefined, options: { frame: boolean }) => void
  /** A trip was picked in the list, or let go of. */
  onPick: (id: string | undefined) => void
  /** A picked trip was clicked again, or its play button. */
  onPlay: (id: string) => void
  /** The list's close button, or an opener while it's open, was pressed. */
  onRequestClose: () => void
  /** An opener was pressed while the list was closed. */
  onRequestOpen: () => void
  openers: readonly HTMLElement[]
  reducedMotion: MediaQueryList
  signal: AbortSignal
}) {
  let exit: Animation | undefined
  let trigger: HTMLElement | undefined
  let dwell: ReturnType<typeof setTimeout> | undefined
  // The row filling up, the row lifted meanwhile, and the row picked.
  let dwelling: HTMLElement | undefined
  let previewed: HTMLElement | undefined
  let picked: HTMLElement | undefined
  // The row whose route the map shows.
  let lifted: HTMLElement | undefined

  list.style.setProperty('--trip-map-dwell', `${DWELL}ms`)

  function isOpen() {
    return !list.hidden && !exit
  }

  function getId(link: HTMLElement) {
    return link.dataset.tripMapTripLink!
  }

  function getPlayButton(link: HTMLElement) {
    return link.parentElement?.querySelector<HTMLElement>('[data-trip-map-trip-play]') ?? null
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

  /** Lifts what's previewed, or else what's picked, flying to it if it's new. */
  function update(options: { frame: boolean }) {
    const next = previewed ?? picked

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

  function setPicked(link: HTMLElement | undefined) {
    if (link === picked) {
      return
    }

    if (picked) {
      delete picked.dataset.tripMapPicked
      picked.ariaCurrent = null
      getPlayButton(picked)?.setAttribute('hidden', '')
    }

    picked = link

    if (link) {
      link.dataset.tripMapPicked = ''
      link.ariaCurrent = 'true'
      getPlayButton(link)?.removeAttribute('hidden')
    }

    setDwelling(undefined)
    previewed = undefined
    update({ frame: Boolean(link) })
    onPick(link && getId(link))
  }

  /** Picks a trip's row, as its route was picked on the map, or lets go of it. */
  function pick(id: string | undefined) {
    const link = id
      ? (list.querySelector<HTMLElement>(`[data-trip-map-trip-link="${CSS.escape(id)}"]`) ??
        undefined)
      : undefined

    if (!isOpen()) {
      return
    }

    setPicked(link)
    link?.scrollIntoView({
      behavior: reducedMotion.matches ? 'auto' : 'smooth',
      block: 'nearest',
    })
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

    exit?.cancel()
    exit = undefined
    list.hidden = false
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

    setPicked(undefined)
    setExpanded(false)

    const animation = list.animate(
      [{}, { opacity: 0, translate: isNarrow() ? '0 2rem' : '1.5rem 0' }],
      { ...EXIT, duration: reducedMotion.matches ? 0 : EXIT.duration },
    )

    exit = animation
    animation.finished.then(
      () => {
        if (exit === animation) {
          exit = undefined
          list.hidden = true
          animation.cancel()
        }
      },
      () => {},
    )
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
      const target = event.target instanceof Element ? event.target : null
      const play = target?.closest<HTMLElement>('[data-trip-map-trip-play]')
      const link = getLink(target)

      if (play?.dataset.tripMapTripPlay) {
        event.preventDefault()
        onPlay(play.dataset.tripMapTripPlay)
      } else if (link && link === picked) {
        event.preventDefault()
        onPlay(getId(link))
      } else if (link) {
        event.preventDefault()
        setPicked(link)
      }
    },
    { signal },
  )
  list.addEventListener(
    'focusin',
    (event) => {
      const link = getLink(event.target)

      // Reached by keyboard: a pointer's focus comes with its click.
      if (link && link !== picked && link.matches(':focus-visible')) {
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
  signal.addEventListener('abort', () => {
    clearTimeout(dwell)
    exit?.cancel()
  })

  return {
    close,
    get isOpen() {
      return isOpen()
    },
    pick,
    show,
  }
}
