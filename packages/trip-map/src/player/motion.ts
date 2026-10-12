import { createElement } from './dom.ts'

// How the trip's cards and photos change: the side card eases between cards'
// heights, and photos (on the side card and the stage's photo card) fade through.

export const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')

// Switching photos fades through: the old one fades out, the frame takes the new one's
// shape, then the new one fades in, so the two are never seen on top of each other.
export const PHOTO_SWITCH = { duration: 480, easing: 'cubic-bezier(0.4, 0, 0.2, 1)' }
// The two fades overlap a little while both are faint, so there's no empty moment
// between them, but never both clearly at once.
export const FADE_OUT: Keyframe[] = [{ opacity: 1 }, { offset: 0.46, opacity: 0 }, { opacity: 0 }]
export const FADE_IN: Keyframe[] = [
  { opacity: 0, transform: 'scale(0.98)' },
  { offset: 0.2, opacity: 0, transform: 'scale(0.98)' },
  { opacity: 1, transform: 'none' },
]
// A card's height eases to the next card's; the old content fades out as the new
// content fades in.
const CARD_SWITCH = { duration: 380, easing: 'cubic-bezier(0.4, 0, 0.2, 1)' }
const CARD_FADE_IN: Keyframe[] = [
  { opacity: 0, transform: 'translateY(0.25rem)' },
  { offset: 0.25, opacity: 0, transform: 'translateY(0.25rem)' },
  { opacity: 1, transform: 'none' },
]

export type Transition = ReturnType<typeof createTransition>

/**
 * One transition at a time: starting the next, or ending this one early, stops its
 * animations where they are and tidies up after it.
 */
export function createTransition() {
  let current: { animations: Animation[]; cleanup: () => void } | undefined

  function end() {
    if (!current) {
      return
    }

    const { animations, cleanup } = current

    current = undefined

    for (const animation of animations) {
      animation.cancel()
    }

    cleanup()
  }

  return {
    end,
    get running() {
      return current !== undefined
    },
    /** Plays `animations`; `cleanup` runs once the first has finished or is ended. */
    run(animations: Animation[], cleanup: () => void) {
      end()
      current = { animations, cleanup }
      void animations[0]?.finished.then(end, () => {})
    },
  }
}

/**
 * Runs `update` (which renders the next card) and eases the side card between the
 * two. The card is anchored at the bottom, so it grows or shrinks upwards; its footer
 * stays pinned there meanwhile instead of being cut off. A copy of the old card fades
 * out as the new one fades in.
 */
export function easeCardChange(
  transition: Transition,
  { body, header, panel }: { body: HTMLElement; header: HTMLElement; panel: HTMLElement },
  update: () => void,
) {
  // Where a change still in progress has got to, so a quick second one carries on.
  const fromHeight = panel.getBoundingClientRect().height
  transition.end()

  const ghost = createElement('div', 'calendar-map-trip-ghost', '')
  const top = header.offsetTop

  ghost.append(header.cloneNode(true), body.cloneNode(true))
  ghost.setAttribute('aria-hidden', 'true')
  ghost.inert = true

  // The copy keeps nothing the player looks up, so it can't be mistaken for the card.
  for (const element of [ghost, ...ghost.querySelectorAll('*')]) {
    element.removeAttribute('id')

    for (const name of element.getAttributeNames()) {
      if (name.startsWith('data-calendar-map')) {
        element.removeAttribute(name)
      }
    }
  }

  update()

  const toHeight = panel.offsetHeight

  ghost.style.top = `${top}px`
  panel.append(ghost)
  panel.toggleAttribute('data-resizing', true)
  transition.run(
    [
      panel.animate([{ height: `${fromHeight}px` }, { height: `${toHeight}px` }], CARD_SWITCH),
      ghost.animate(FADE_OUT, CARD_SWITCH),
      header.animate(CARD_FADE_IN, CARD_SWITCH),
      body.animate(CARD_FADE_IN, CARD_SWITCH),
    ],
    () => {
      ghost.remove()
      panel.removeAttribute('data-resizing')
    },
  )
}

/**
 * Runs `update` (which gives `photo` the next image and size) and fades through: the
 * old image fades out, the frame eases to the new size with both filling it, then the
 * new one fades in, along with its live clip if `update` started one (`getLive`).
 */
export function easePhotoChange(
  transition: Transition,
  photo: HTMLImageElement,
  getLive: () => HTMLVideoElement | undefined,
  update: () => void,
) {
  const frame = photo.parentElement

  if (!frame) {
    transition.end()
    update()
    return
  }

  // Where a switch still in progress has got to, so a quick second one carries on.
  const from = frame.getBoundingClientRect()
  transition.end()

  const outgoing = photo.cloneNode() as HTMLImageElement

  update()

  const to = { height: frame.offsetHeight, width: frame.offsetWidth }
  // While the frame changes shape, each photo fills it rather than being cut off.
  const fill =
    'position:absolute;inset:0;width:100%;height:100%;max-width:none;max-height:none;object-fit:cover;pointer-events:none'
  const live = getLive()
  const clip = live?.parentElement === frame ? live : undefined
  // The size the new photo was given, to put back once it's done filling the frame.
  const size = { height: photo.style.height, width: photo.style.width }

  outgoing.style.cssText = fill
  photo.style.cssText = fill
  frame.append(outgoing)
  transition.run(
    [
      frame.animate(
        [
          { height: `${from.height}px`, width: `${from.width}px` },
          { height: `${to.height}px`, width: `${to.width}px` },
        ],
        PHOTO_SWITCH,
      ),
      outgoing.animate(FADE_OUT, PHOTO_SWITCH),
      photo.animate(FADE_IN, PHOTO_SWITCH),
      ...(clip ? [clip.animate(FADE_IN, PHOTO_SWITCH)] : []),
    ],
    () => {
      outgoing.remove()
      photo.style.cssText = ''
      photo.style.width = size.width
      photo.style.height = size.height
    },
  )
}
