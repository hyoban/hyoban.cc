import { drawRouteGlyph } from '@/trip-route-glyph'

// A trip's card in a year's review and the trip's page as one through the router's view
// transition: going from the card to the page, its cover becomes the page's photos, its
// name the sidebar's title and its route the one standing in for the map's (see
// trip-route-glyph.ts); coming back, the photos and the title go back into the card. Only
// the trip gone to or from is named, on each side just for the transition, so the rest
// of the page's cards aren't captured and the names never meet twice.

const TRIP_PATH = /^\/trip\/([^/]+)\/$/

type Named = HTMLElement | SVGElement
type Parts = Partial<Record<'cover' | 'title' | 'route', Element | null | undefined>>

function getCardParts(root: ParentNode, id: string): Parts | undefined {
  const card = [...root.querySelectorAll('[data-trip-card]')].find(
    (element) => element.getAttribute('data-trip-card') === id,
  )
  return (
    card && {
      cover: card,
      title: card.querySelector('[data-trip-card-title]'),
      route: card.querySelector('[data-trip-card-route]'),
    }
  )
}

function getPageParts(root: ParentNode, id: string): Parts | undefined {
  const details = root.querySelector('[data-trip-map-trip-details]:not([hidden])')
  const content = details?.querySelector('[data-trip-map-trip-details-trip]')
  if (content?.getAttribute('data-trip-map-trip-details-trip') !== id) return undefined

  return {
    cover: content.querySelector('.trip-cover-collage'),
    title: details!.querySelector('[data-trip-map-trip-details-title]'),
    route: root.querySelector('[data-trip-map-route-glyph]'),
  }
}

/** Names the parts that move; the route only going to the page, gone by coming back. */
function nameParts(parts: Parts | undefined, toPage: boolean) {
  const named: Named[] = []
  for (const [part, element] of Object.entries(parts ?? {})) {
    if (part === 'route' && !toPage) continue
    if (element instanceof HTMLElement || element instanceof SVGElement) {
      element.style.viewTransitionName = `trip-${part}`
      element.toggleAttribute('data-trip-transition', true)
      named.push(element)
    }
  }
  return named
}

/** The trip the page leaving is the card or the page of, and where it goes to. */
let pending: { id: string; toPage: boolean } | undefined
let named: Named[] = []

document.addEventListener('astro:before-preparation', (event) => {
  pending = undefined
  const to = event.to.pathname.match(TRIP_PATH)?.[1]
  const from = event.from.pathname.match(TRIP_PATH)?.[1]
  const toPage = Boolean(to && to !== from)
  if (!toPage && !(from && !to)) return

  const id = decodeURIComponent((toPage ? to : from)!)
  const parts = toPage ? getCardParts(document, id) : getPageParts(document, id)
  if (parts) {
    pending = { id, toPage }
    named = nameParts(parts, toPage)
  }
})

document.addEventListener('astro:before-swap', (event) => {
  if (!pending) return
  const { id, toPage } = pending
  pending = undefined
  const parts = toPage ? getPageParts(event.newDocument, id) : getCardParts(event.newDocument, id)
  // Drawn now, as the page is seen in the transition, at the window's size: the map fills it.
  if (toPage && parts?.route instanceof SVGSVGElement) {
    drawRouteGlyph(parts.route, innerWidth, innerHeight)
  }

  const all = [...named, ...nameParts(parts, toPage)]
  named = []
  void event.viewTransition.finished
    .catch(() => {})
    .then(() => {
      for (const element of all) {
        element.style.viewTransitionName = ''
        element.removeAttribute('data-trip-transition')
      }
    })
})
