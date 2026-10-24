import { t, type Messages } from '../messages.ts'
import { isPlainClick } from './link-click.ts'
import type { Period } from './period.ts'
import { createSheet } from './sheet.ts'

// The Moments of a year, or a month of one, beside the map where a place's drawer sits,
// while the address narrows the map to that period: the site's HTML fragment for the
// year, newest first, fetched the first time and shown a month at a time once one is
// picked. Its months (`[data-trip-map-month]`) pick one, and `[data-trip-map-month-all]`
// the whole year again, each a link to the address it goes to; its places
// (`[data-trip-map-place-link]`) fly the map to them.
//
// The fragment holds a `[data-trip-map-moments-month="<month>"]` per month, with the
// number of its Moments in `data-trip-map-count`, and its heading, hidden while the
// month is picked, in `[data-trip-map-moments-heading]`.

export type MomentList = ReturnType<typeof createMomentList>

export function createMomentList({
  closeButton,
  content,
  eyebrow,
  getMapPath,
  getMomentsUrl,
  getPlaceCount,
  narrow,
  list,
  messages,
  onError,
  onPickMonth,
  onPlace,
  onRequestClose,
  signal,
  title,
}: {
  closeButton: HTMLButtonElement | null
  content: HTMLElement
  eyebrow: HTMLElement | null
  /** The map's address narrowed to a period, which the months link to. */
  getMapPath: (period: Period) => string
  getMomentsUrl: (year: number) => string
  /** How many places the map shows for the period. */
  getPlaceCount: () => number
  /** Whether the screen is a phone's, where the list comes up from the bottom. */
  narrow: MediaQueryList
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
  signal: AbortSignal
  title: HTMLElement | null
}) {
  // Each year's fragment, fetched once; a failed fetch is tried again next time.
  const html = new Map<number, string>()
  const requests = new Map<number, Promise<string | undefined>>()
  let period: Period | undefined
  // The year whose Moments are in the list.
  let shownYear: number | undefined
  // The one month of it the list holds, while it holds no other: the rest come with the
  // year's fragment.
  let onlyMonth: number | undefined

  let shown = !list.hidden
  // Swiped away, it closes as its close button does.
  const sheet = createSheet(list, {
    narrow,
    onSwipeClose: onRequestClose,
    // As the site's CSS sets it: shorter than a place's, its routes in view above it.
    phoneHeight: { max: 36, share: 0.6 },
    signal,
  })

  // The page may come with a year's Moments in it already, or just a month's.
  const renderedYear = Number(content.dataset.tripMapMomentsYear)

  if (renderedYear) {
    shownYear = renderedYear
    onlyMonth = Number(content.dataset.tripMapMomentsMonthOnly) || undefined

    if (onlyMonth === undefined) {
      html.set(renderedYear, content.innerHTML)
    }
  }

  function isOpen() {
    return shown
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
      // A month on its own is the list's title already.
      section
        .querySelector<HTMLElement>('[data-trip-map-moments-heading]')
        ?.toggleAttribute('hidden', period.month !== undefined)

      if (shown) {
        count += Number(section.dataset.tripMapCount) || 0
      }
    }

    const { month: pickedMonth, year } = period
    const all = content.querySelector<HTMLAnchorElement>('[data-trip-map-month-all]')

    if (all) {
      all.href = getMapPath({ year })
      setCurrent(all, pickedMonth === undefined)
    }

    // The month picked links back to the whole year, as pressing it again goes.
    for (const link of content.querySelectorAll<HTMLAnchorElement>('[data-trip-map-month]')) {
      const month = Number(link.dataset.tripMapMonth)

      link.href = getMapPath(month === pickedMonth ? { year } : { month, year })
      setCurrent(link, month === pickedMonth)
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
    onlyMonth = undefined
    applyMonth()
  }

  /** Opens the list on `next`, or moves it there, fetching its year if need be. */
  function show(next: Period, options: { focus?: boolean } = {}) {
    const opening = !isOpen()
    const yearChanged = next.year !== period?.year || shownYear !== next.year
    const monthChanged = next.month !== period?.month
    // The list holds only another month of the year, or of a year shown whole.
    const yearNeeded =
      shownYear !== next.year || (onlyMonth !== undefined && next.month !== onlyMonth)

    period = next
    shown = true
    sheet.open()

    if (title) {
      title.textContent =
        next.month === undefined
          ? t(messages, 'yearItem', { year: next.year })
          : t(messages, 'monthItem', { month: next.month, year: next.year })
    }

    if (yearNeeded) {
      // The month in the list stays meanwhile; another year's give way to a message.
      if (shownYear !== next.year) {
        if (eyebrow) {
          eyebrow.textContent = ''
        }

        setMessage(messages.drawerLoading)
      }

      content.setAttribute('aria-busy', 'true')
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

    shown = false
    sheet.close()
  }

  closeButton?.addEventListener('click', onRequestClose, { signal })
  content.addEventListener(
    'click',
    (event) => {
      if (!isPlainClick(event)) {
        return
      }

      const target = event.target instanceof Element ? event.target : null
      const month = target?.closest<HTMLElement>('[data-trip-map-month]')
      const all = target?.closest('[data-trip-map-month-all]')
      const place = target?.closest<HTMLElement>('[data-trip-map-place-link]')

      if (all) {
        event.preventDefault()
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

/** Marks the link to the period shown as the current one, or not. */
function setCurrent(link: HTMLElement, current: boolean) {
  if (current) {
    link.setAttribute('aria-current', 'true')
  } else {
    link.removeAttribute('aria-current')
  }
}
