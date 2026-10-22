import { t, type Messages } from '../messages.ts'
import type { Period } from './period.ts'

// The Moments of a year, or a month of one, beside the map where a place's drawer sits,
// while the address narrows the map to that period: the site's HTML fragment for the
// year, newest first, fetched the first time and shown a month at a time once one is
// picked. Its months (`[data-trip-map-month]`) pick one, and `[data-trip-map-month-all]`
// the whole year again; its places (`[data-trip-map-place-link]`) fly the map to them.
//
// The fragment holds a `[data-trip-map-moments-month="<month>"]` per month, with the
// number of its Moments in `data-trip-map-count`.

// The list sliding away as it closes, like the drawer (see styles.css).
const EXIT = { duration: 200, easing: 'cubic-bezier(0.4, 0, 1, 1)' }

export type MomentList = ReturnType<typeof createMomentList>

export function createMomentList({
  closeButton,
  content,
  eyebrow,
  getMomentsUrl,
  getPlaceCount,
  isNarrow,
  list,
  messages,
  onError,
  onPickMonth,
  onPlace,
  onRequestClose,
  reducedMotion,
  signal,
  title,
}: {
  closeButton: HTMLButtonElement | null
  content: HTMLElement
  eyebrow: HTMLElement | null
  getMomentsUrl: (year: number) => string
  /** How many places the map shows for the period. */
  getPlaceCount: () => number
  isNarrow: () => boolean
  list: HTMLElement
  messages: Messages
  /** A year's Moments couldn't be fetched. */
  onError: (year: number, cause: unknown) => void
  /** A month was pressed: that month, or the whole year again when it was the one shown. */
  onPickMonth: (month: number | undefined) => void
  /** A place a Moment was at was pressed; false if the map doesn't show it. */
  onPlace: (id: string) => boolean
  /** The close button was pressed. */
  onRequestClose: () => void
  reducedMotion: MediaQueryList
  signal: AbortSignal
  title: HTMLElement | null
}) {
  // Each year's fragment, fetched once; a failed fetch is tried again next time.
  const html = new Map<number, string>()
  const requests = new Map<number, Promise<string | undefined>>()
  let exit: Animation | undefined
  let period: Period | undefined
  // The year whose Moments are in the list.
  let shownYear: number | undefined

  // The page may come with a year's Moments in it already.
  const renderedYear = Number(content.dataset.tripMapMomentsYear)

  if (renderedYear) {
    shownYear = renderedYear
    html.set(renderedYear, content.innerHTML)
  }

  function isOpen() {
    return !list.hidden && !exit
  }

  function load(year: number) {
    let request = requests.get(year)

    if (html.has(year)) {
      return Promise.resolve(html.get(year))
    }

    if (!request) {
      request = fetch(getMomentsUrl(year), { signal })
        .then((response) => {
          if (!response.ok) {
            throw new Error(`${response.status} ${response.statusText}`)
          }

          return response.text()
        })
        .then(
          (text) => {
            html.set(year, text)
            return text
          },
          (cause: unknown) => {
            requests.delete(year)

            if (!signal.aborted) {
              onError(year, cause)
            }

            return undefined
          },
        )
      requests.set(year, request)
    }

    return request
  }

  function setMessage(text: string) {
    const message = document.createElement('p')
    message.className = 'trip-map-drawer__message'
    message.textContent = text
    content.replaceChildren(message)
  }

  function getMonths() {
    return [...content.querySelectorAll<HTMLElement>('[data-trip-map-moments-month]')]
  }

  /** Shows the period's month, or every month of its year, and says how many Moments. */
  function applyMonth() {
    if (!period || shownYear !== period.year) {
      return
    }

    let count = 0

    for (const section of getMonths()) {
      const shown =
        period.month === undefined || Number(section.dataset.tripMapMomentsMonth) === period.month

      section.hidden = !shown

      if (shown) {
        count += Number(section.dataset.tripMapCount) || 0
      }
    }

    content
      .querySelector('[data-trip-map-month-all]')
      ?.setAttribute('aria-pressed', String(period.month === undefined))

    for (const button of content.querySelectorAll<HTMLElement>('[data-trip-map-month]')) {
      button.setAttribute(
        'aria-pressed',
        String(Number(button.dataset.tripMapMonth) === period.month),
      )
    }

    if (eyebrow) {
      const places = getPlaceCount()

      // A period's Moments may all be somewhere off the map, which is left empty.
      eyebrow.textContent = places
        ? t(messages, 'momentsEyebrow', { count, places })
        : t(messages, 'momentsEyebrowNoPlaces', { count })
    }
  }

  function render(year: number, text: string | undefined) {
    content.removeAttribute('aria-busy')

    if (text === undefined) {
      shownYear = undefined
      setMessage(messages.momentsFailed)
      return
    }

    const template = document.createElement('template')
    template.innerHTML = text
    content.replaceChildren(template.content)
    shownYear = year
    applyMonth()
  }

  /** Opens the list on `next`, or moves it there, fetching its year if need be. */
  function show(next: Period, options: { focus?: boolean } = {}) {
    const opening = !isOpen()
    const yearChanged = next.year !== period?.year || shownYear !== next.year
    const monthChanged = next.month !== period?.month

    period = next
    exit?.cancel()
    exit = undefined
    list.hidden = false

    if (title) {
      title.textContent =
        next.month === undefined
          ? t(messages, 'yearItem', { year: next.year })
          : t(messages, 'monthItem', { month: next.month, year: next.year })
    }

    if (yearChanged && shownYear !== next.year) {
      if (eyebrow) {
        eyebrow.textContent = ''
      }

      content.setAttribute('aria-busy', 'true')
      setMessage(messages.drawerLoading)
      void load(next.year).then((text) => {
        if (period?.year === next.year) {
          render(next.year, text)
        }
      })
    } else {
      applyMonth()
    }

    if (yearChanged || monthChanged) {
      content.scrollTop = 0
    }

    if (opening && options.focus !== false) {
      ;(closeButton ?? list).focus({ preventScroll: true })
    }
  }

  function close() {
    if (!isOpen()) {
      return
    }

    period = undefined

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
  }

  closeButton?.addEventListener('click', onRequestClose, { signal })
  content.addEventListener(
    'click',
    (event) => {
      const target = event.target instanceof Element ? event.target : null
      const month = target?.closest<HTMLElement>('[data-trip-map-month]')
      const all = target?.closest('[data-trip-map-month-all]')
      const place = target?.closest<HTMLElement>('[data-trip-map-place-link]')

      if (all) {
        onPickMonth(undefined)
      } else if (month) {
        event.preventDefault()

        const picked = Number(month.dataset.tripMapMonth)
        onPickMonth(picked === period?.month ? undefined : picked)
      } else if (place?.dataset.tripMapPlaceLink && onPlace(place.dataset.tripMapPlaceLink)) {
        // A place off the map is left to its link.
        event.preventDefault()
      }
    },
    { signal },
  )
  signal.addEventListener('abort', () => exit?.cancel())

  return {
    close,
    get isOpen() {
      return isOpen()
    },
    show,
  }
}
