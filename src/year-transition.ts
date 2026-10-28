import { CALENDAR_DESKTOP_MEDIA } from '@/consts'

// A year's review and the pages around it as one through the router's view transition:
// a year's label on the home page, the life page or the calendar's picker grows into the
// review's huge year, and shrinks back into it going back; and a day on the review's wall
// opens into the same day in the calendar, its tile into the day's picture there (in the
// month's grid on a wide screen, the day's first photo on a phone), and back. As with
// trip-transition.ts, only what moves is named, on each side just for the transition.

const YEAR_PATH = /^\/(\d{4})\/$/
const REVIEW_PATH = /^\/(?:\d{4}|life)\/$/
const DAY_PATH = /^\/calendar\/\d{4}\/\d{2}\/\d{2}\/$/

type Part = { name: string; element: HTMLElement }

/** The year's label in a link to its review: the year's own text where it has one. */
function getYearLabel(root: ParentNode, year: string) {
  for (const link of root.querySelectorAll<HTMLAnchorElement>(
    `:is(.post-year h2, .year-years, .month-picker h2) a[href="/${year}/"]`,
  )) {
    const label = link.querySelector<HTMLElement>('.year-years__year') ?? link
    if (label.textContent?.trim().startsWith(year)) return label
  }
  return undefined
}

function getYearHero(root: ParentNode) {
  return root.querySelector<HTMLElement>('.year-hero h1') ?? undefined
}

function getWallDay(root: ParentNode, path: string) {
  return root.querySelector<HTMLElement>(`.year-mosaic__day a[href="${path}"] img`) ?? undefined
}

function getCalendarDay(root: ParentNode) {
  return (
    (matchMedia(CALENDAR_DESKTOP_MEDIA).matches
      ? root.querySelector<HTMLElement>('.calendar-day--selected .calendar-day__picture img')
      : root.querySelector<HTMLElement>('[data-calendar-drawer] .day-drawer__content img')) ??
    undefined
  )
}

/** Whether `element` is on the screen now, so moving it from there reads as one. */
function isInSight(element: HTMLElement | undefined): element is HTMLElement {
  if (!element) return false
  const box = element.getBoundingClientRect()
  return box.width > 0 && box.bottom > 0 && box.top < innerHeight
}

/** How to find, in the page coming, the parts named in the page going. */
let pending: ((root: Document) => Part[]) | undefined
let named: Part[] = []

function name(parts: Part[]) {
  for (const { name, element } of parts) element.style.viewTransitionName = name
  return parts
}

document.addEventListener('astro:before-preparation', (event) => {
  pending = undefined
  const from = event.from.pathname
  const to = event.to.pathname
  const source = event.sourceElement

  if (REVIEW_PATH.test(from) && DAY_PATH.test(to)) {
    // Into a day from its tile on the wall, before the year shrinking back out: the year is
    // on screen above the wall too.
    const tile = getWallDay(document, to)
    if (isInSight(tile)) {
      named = name([{ name: 'year-day', element: tile }])
      pending = (root) => {
        const day = getCalendarDay(root)
        return day ? [{ name: 'year-day', element: day }] : []
      }
      return
    }
  }

  const toYear = to.match(YEAR_PATH)?.[1]
  const fromYear = from.match(YEAR_PATH)?.[1]
  if (toYear && toYear !== fromYear && source instanceof HTMLAnchorElement) {
    // Into a review from its year's label.
    const label = getYearLabel(document, toYear)
    if (label && source.contains(label) && isInSight(label)) {
      named = name([{ name: 'year-numeral', element: label }])
      pending = (root) => {
        const hero = getYearHero(root)
        if (!hero) return []
        // Already where it is, it doesn't rise in too.
        hero.style.animation = 'none'
        return [{ name: 'year-numeral', element: hero }]
      }
    }
  } else if (fromYear && !to.match(YEAR_PATH)) {
    // Out of a review, back into its year's label if the page going to has one.
    const hero = getYearHero(document)
    if (isInSight(hero)) {
      named = name([{ name: 'year-numeral', element: hero }])
      pending = (root) => {
        const label = getYearLabel(root, fromYear)
        return label ? [{ name: 'year-numeral', element: label }] : []
      }
    }
  }
  if (pending) return

  if (DAY_PATH.test(from) && REVIEW_PATH.test(to)) {
    // Back out of the day onto its tile.
    const day = getCalendarDay(document)
    if (isInSight(day)) {
      named = name([{ name: 'year-day', element: day }])
      pending = (root) => {
        const tile = getWallDay(root, from)
        return tile ? [{ name: 'year-day', element: tile }] : []
      }
    }
  }
})

document.addEventListener('astro:before-swap', (event) => {
  const find = pending
  pending = undefined
  if (!find) return
  const all = [...named, ...name(find(event.newDocument))]
  named = []
  void event.viewTransition.finished
    .catch(() => {})
    .then(() => {
      for (const { element } of all) element.style.viewTransitionName = ''
    })
})
