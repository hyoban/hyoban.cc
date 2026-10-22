import type { MapElements } from '../elements.ts'
import { t, type Messages } from '../messages.ts'
import type { PlaceMarker } from './place-markers.ts'

// The drawer a place opens with its Moments: an HTML fragment fetched from the site
// the first time, and a lightbox for its photos (and the Moments list's) when the site
// provides one.

/** Shows a drawer item's media in the lightbox: a still, or a live photo's clip. */
export type LightboxMedia = { show: (item: HTMLElement) => void }

// The drawer sliding away as it closes; it slides in from the same spot (see styles.css).
const DRAWER_EXIT = { duration: 200, easing: 'cubic-bezier(0.4, 0, 1, 1)' }

export type PlaceDrawer = ReturnType<typeof createPlaceDrawer>

export function createPlaceDrawer({
  createLightboxMedia,
  elements,
  getPlaceUrl,
  isNarrow,
  messages,
  onClosed,
  onError,
  onOpen,
  onPickYear,
  onRestoreFocus,
  reducedMotion,
  signal,
}: {
  createLightboxMedia?:
    | ((dialog: HTMLDialogElement, signal: AbortSignal) => LightboxMedia)
    | undefined
  elements: MapElements
  getPlaceUrl: (id: string) => string
  isNarrow: () => boolean
  messages: Messages
  /** The drawer started closing: the map can drop the room it left for it. */
  onClosed: () => void
  /** A place's content couldn't be fetched. */
  onError: (id: string, cause: unknown) => void
  /** A place opened; the map may move to it. */
  onOpen: (place: PlaceMarker, options: { moveMap: boolean }) => void
  /** A year in the drawer's line of totals was pressed, to narrow the map to it. */
  onPickYear: (year: number) => void
  /** Puts focus back on what opened the drawer. */
  onRestoreFocus: (trigger: HTMLButtonElement) => void
  reducedMotion: MediaQueryList
  signal: AbortSignal
}) {
  const { drawer, drawerClose, drawerContent, drawerEyebrow, drawerTitle, lightbox, page } =
    elements
  // Each place's drawer content, fetched once; a failed fetch is tried again next time.
  const html = new Map<string, string>()
  const requests = new Map<string, Promise<string | undefined>>()
  const lightboxMedia = lightbox && createLightboxMedia?.(lightbox, signal)
  let activeId: string | undefined
  let activeTrigger: HTMLButtonElement | undefined
  let exit: Animation | undefined
  let lightboxIndex = 0
  let lightboxItems: HTMLElement[] = []

  function load(id: string) {
    let request = requests.get(id)

    if (!request) {
      request = fetch(getPlaceUrl(id), { signal })
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

  function createMessage(text: string) {
    const message = document.createElement('p')
    message.className = 'trip-map-drawer__message'
    message.textContent = text
    return message
  }

  function renderContent(text: string | undefined) {
    const template = document.createElement('template')

    if (text === undefined) {
      drawerContent.replaceChildren(createMessage(messages.drawerFailed))
    } else {
      template.innerHTML = text
      drawerContent.replaceChildren(template.content)
    }

    drawerContent.removeAttribute('aria-busy')
  }

  /** The place's totals, each of its years a button narrowing the map to that year. */
  function setEyebrow(place: PlaceMarker) {
    const [before = '', after = ''] = t(messages, 'placeEyebrow', { count: place.count }).split(
      '{years}',
    )
    const parts: (Node | string)[] = [before]

    for (const [index, year] of place.years.entries()) {
      const button = document.createElement('button')

      button.type = 'button'
      button.className = 'trip-map-drawer__year'
      button.dataset.tripMapDrawerYear = String(year)
      button.textContent = t(messages, 'yearItem', { year })
      parts.push(...(index > 0 ? [messages.yearSeparator] : []), button)
    }

    drawerEyebrow.replaceChildren(...parts, after)
  }

  function closeLightbox() {
    if (lightbox?.open) {
      lightbox.close()
    }
  }

  function open(
    place: PlaceMarker,
    options: { focus?: boolean; moveMap?: boolean; trigger?: HTMLButtonElement | undefined } = {},
  ) {
    closeLightbox()

    activeId = place.id
    activeTrigger = options.trigger ?? activeTrigger
    drawerTitle.textContent = place.name
    setEyebrow(place)
    exit?.cancel()
    exit = undefined
    drawer.hidden = false

    if (html.has(place.id)) {
      renderContent(html.get(place.id))
    } else {
      drawerContent.setAttribute('aria-busy', 'true')
      drawerContent.replaceChildren(createMessage(messages.drawerLoading))
      void load(place.id).then((text) => {
        if (activeId === place.id) {
          renderContent(text)
        }
      })
    }

    onOpen(place, { moveMap: options.moveMap !== false })

    if (options.focus !== false) {
      drawerClose.focus({ preventScroll: true })
    }
  }

  function close(options: { restoreFocus?: boolean } = {}) {
    if (drawer.hidden || exit) {
      return
    }

    closeLightbox()
    activeId = undefined

    // Slides away first, then hides and lets go of its content; a place opened
    // meanwhile cancels the slide and keeps the drawer. A script animation, as going
    // back to the map page has the router put the map back into the page, which would
    // end a CSS transition on the spot.
    const animation = drawer.animate(
      [{}, { opacity: 0, translate: isNarrow() ? '0 2rem' : '1.5rem 0' }],
      { ...DRAWER_EXIT, duration: reducedMotion.matches ? 0 : DRAWER_EXIT.duration },
    )

    exit = animation
    animation.finished.then(
      () => {
        if (exit === animation) {
          exit = undefined
          drawer.hidden = true
          drawerContent.replaceChildren()
          animation.cancel()
        }
      },
      () => {},
    )
    onClosed()

    if (options.restoreFocus !== false && activeTrigger?.isConnected && !activeTrigger.hidden) {
      onRestoreFocus(activeTrigger)
    }
  }

  function showLightboxItem(index: number) {
    if (!lightbox || !lightboxMedia || lightboxItems.length === 0) {
      return
    }

    lightboxIndex = (index + lightboxItems.length) % lightboxItems.length
    const item = lightboxItems[lightboxIndex]

    if (!item) {
      return
    }

    lightboxMedia.show(item)

    if (!lightbox.open) {
      lightbox.showModal()
    }
  }

  page.addEventListener(
    'click',
    (event) => {
      const target = event.target

      if (!(target instanceof Element)) {
        return
      }

      const item = target.closest<HTMLElement>('[data-lightbox-item]')
      // The Moments list beside the map shows its photos in the same lightbox.
      const side = [drawer, elements.moments].find((element) => element?.contains(item))

      if (!item || !side) {
        return
      }

      lightboxItems = [...side.querySelectorAll<HTMLElement>('[data-lightbox-item]')]
      showLightboxItem(lightboxItems.indexOf(item))
    },
    { signal },
  )

  drawerEyebrow.addEventListener(
    'click',
    (event) => {
      const year = (event.target as Element | null)?.closest<HTMLElement>(
        '[data-trip-map-drawer-year]',
      )?.dataset.tripMapDrawerYear

      if (year) {
        onPickYear(Number(year))
      }
    },
    { signal },
  )

  if (lightbox) {
    lightbox
      .querySelector('[data-trip-map-lightbox-close]')
      ?.addEventListener('click', () => lightbox.close(), { signal })
    lightbox
      .querySelector('[data-trip-map-lightbox-previous]')
      ?.addEventListener('click', () => showLightboxItem(lightboxIndex - 1), { signal })
    lightbox
      .querySelector('[data-trip-map-lightbox-next]')
      ?.addEventListener('click', () => showLightboxItem(lightboxIndex + 1), { signal })
    lightbox.addEventListener(
      'click',
      (event) => {
        if (event.target === lightbox) {
          lightbox.close()
        }
      },
      { signal },
    )
    lightbox.addEventListener(
      'keydown',
      (event) => {
        if (event.key === 'ArrowLeft') {
          showLightboxItem(lightboxIndex - 1)
        } else if (event.key === 'ArrowRight') {
          showLightboxItem(lightboxIndex + 1)
        }
      },
      { signal },
    )
  }

  signal.addEventListener('abort', () => exit?.cancel())

  return {
    get activeId() {
      return activeId
    },
    close,
    /** Whether a photo is open over the drawer. */
    get lightboxOpen() {
      return lightbox?.open ?? false
    },
    load,
    open,
    /** Remembers what to put focus back on; set before a place opens from the URL. */
    set trigger(button: HTMLButtonElement | undefined) {
      activeTrigger = button ?? activeTrigger
    },
  }
}
