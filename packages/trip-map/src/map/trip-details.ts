import { formatDateRange } from '../format.ts'
import type { Messages } from '../messages.ts'
import type { TripSummary } from '../player/types.ts'
import { createSheet } from './sheet.ts'

// A trip's sidebar, opened from its row in the trip list or its route on the map while
// the address is the trip's own (`/trip/<id>/`): the site's HTML fragment for it, fetched
// the first time, with its dates and title above. Its `[data-trip-map-stop-play]` links
// play it (see trip-map.ts), and its places (`[data-trip-map-place-link]`) fly the map
// to them.

export type TripDetails = ReturnType<typeof createTripDetails>

export function createTripDetails({
  closeButton,
  content,
  eyebrow,
  getUrl,
  narrow,
  list,
  messages,
  onError,
  onPlace,
  onRequestClose,
  signal,
  title,
}: {
  closeButton: HTMLButtonElement | null
  content: HTMLElement
  eyebrow: HTMLElement | null
  /** A trip's sidebar content, an HTML fragment. */
  getUrl: (id: string) => string
  /** Whether the screen is a phone's, where the list comes up from the bottom. */
  narrow: MediaQueryList
  list: HTMLElement
  messages: Messages
  /** A trip's content couldn't be fetched. */
  onError: (id: string, cause: unknown) => void
  /** A place a Moment was at was pressed; false if the map doesn't show it. */
  onPlace: (id: string) => boolean
  /** The close button was pressed. */
  onRequestClose: () => void
  signal: AbortSignal
  title: HTMLElement | null
}) {
  // Each trip's fragment, fetched once; a failed fetch is tried again next time.
  const html = new Map<string, string>()
  const requests = new Map<string, Promise<string | undefined>>()
  let activeId: string | undefined
  // The trip whose content is in the sidebar.
  let shownId: string | undefined

  let shown = !list.hidden
  // Swiped away, it closes as its close button does.
  const sheet = createSheet(list, { narrow, onSwipeClose: onRequestClose, signal })

  // The page may come with a trip's content in it already.
  const renderedId = content.dataset.tripMapTripDetailsTrip

  if (renderedId) {
    shownId = renderedId
    html.set(renderedId, content.innerHTML)
  }

  function isOpen() {
    return shown
  }

  function load(id: string) {
    if (html.has(id)) {
      return Promise.resolve(html.get(id))
    }

    let request = requests.get(id)

    if (!request) {
      request = fetch(getUrl(id), { signal })
        .then((response) => {
          if (!response.ok) {
            throw new Error(`${response.status} ${response.statusText}`)
          }

          return response.text()
        })
        .then(
          (text) => {
            html.set(id, text)
            return text
          },
          (cause: unknown) => {
            requests.delete(id)

            if (!signal.aborted) {
              onError(id, cause)
            }

            return undefined
          },
        )
      requests.set(id, request)
    }

    return request
  }

  function setMessage(text: string) {
    const message = document.createElement('p')
    message.className = 'trip-map-drawer__message'
    message.textContent = text
    content.replaceChildren(message)
  }

  function render(id: string, text: string | undefined) {
    content.removeAttribute('aria-busy')

    if (text === undefined) {
      shownId = undefined
      setMessage(messages.tripDetailsFailed)
      return
    }

    const template = document.createElement('template')
    template.innerHTML = text
    content.replaceChildren(template.content)
    shownId = id
  }

  /** Opens the sidebar on a trip, or moves it there, fetching its content if need be. */
  function show(trip: TripSummary, options: { focus?: boolean } = {}) {
    const opening = !isOpen()
    const changed = trip.id !== activeId

    activeId = trip.id
    shown = true
    sheet.open()

    if (title) {
      title.textContent = trip.title
    }

    if (eyebrow) {
      eyebrow.textContent = formatDateRange(trip.start, trip.end, messages.locale)
    }

    if (shownId !== trip.id) {
      content.setAttribute('aria-busy', 'true')
      setMessage(messages.drawerLoading)
      void load(trip.id).then((text) => {
        if (activeId === trip.id) {
          render(trip.id, text)
        }
      })
    }

    if (changed) {
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

    activeId = undefined

    shown = false
    sheet.close()
  }

  closeButton?.addEventListener('click', onRequestClose, { signal })
  content.addEventListener(
    'click',
    (event) => {
      const target = event.target instanceof Element ? event.target : null
      const place = target?.closest<HTMLElement>('[data-trip-map-place-link]')

      // A place off the map is left to its link.
      if (place?.dataset.tripMapPlaceLink && onPlace(place.dataset.tripMapPlaceLink)) {
        event.preventDefault()
      }
    },
    { signal },
  )

  return {
    /** The trip open, if any. */
    get activeId() {
      return isOpen() ? activeId : undefined
    },
    close,
    /** Starts fetching a trip's content: pointing at it is a good hint it'll be opened. */
    load,
    get isOpen() {
      return isOpen()
    },
    /** The drawer it is, for one to open over it. */
    sheet,
    show,
  }
}
