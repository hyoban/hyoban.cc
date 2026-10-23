// Material 3's multi-browse carousel for the year's places: the items in a row take a
// large, a medium or a small size by where they are, so a few show whole and the next
// peek in behind them, and scrolling grows each as it comes in and shrinks it as it goes.
// Items keep one size in the page, so the scroller snaps to them; each is drawn moved
// and masked to its place, its photo staying centred under the mask. At the start the
// large items lead, [L, L, M, S]; over the last steps the row turns round to end on
// them, [S, M, L, L]. With reduced motion the row stays a plain one of same-sized items.

const TARGET_LARGE = 176
/** Material's small items are 40–56dp. */
const SMALL = 48
const GAP = 8
const MEDIUM_RATIO = 0.6
/** How many steps of scrolling the row takes to turn round at its end. */
const TURN = 2

export type Keylines = Readonly<{
  large: number
  medium: number
  small: number
  gap: number
  /** How many large items show at once. */
  largeCount: number
  /** How far one item scrolls the row. */
  stride: number
}>

/** The sizes that fill `width` with large items, then a medium and a small one. */
export function getKeylines(width: number): Keylines {
  const largeCount = Math.max(
    1,
    Math.round((width - SMALL - GAP) / (TARGET_LARGE + GAP) - MEDIUM_RATIO),
  )
  const large = (width - SMALL - GAP * (largeCount + 1)) / (largeCount + MEDIUM_RATIO)

  return {
    large,
    medium: large * MEDIUM_RATIO,
    small: SMALL,
    gap: GAP,
    largeCount,
    stride: large + GAP,
  }
}

/** How many steps the row scrolls: none while every item fits large. */
export function getSteps(count: number, keylines: Keylines) {
  return Math.max(0, count - keylines.largeCount)
}

/** Each item's left edge in the row and its size, scrolled `position` items along. */
export function placeItems(count: number, keylines: Keylines, position: number) {
  const { large, medium, small, gap, largeCount } = keylines
  const steps = getSteps(count, keylines)
  const turn = Math.min(steps, TURN)
  const t = turn > 0 ? clamp((position - (steps - turn)) / turn, 0, 1) : 0

  // The start's slots, 0 the first large one: large ones, a medium, then small ones.
  const startSize = (slot: number) =>
    slot < 0 ? small : slot < largeCount ? large : slot === largeCount ? medium : small
  // The end's: a small and a medium before the large ones, small ones after.
  const endSize = (slot: number) =>
    slot === -1 ? medium : slot >= 0 && slot < largeCount ? large : small
  const startAt = slotPositions(startSize, 0, gap)
  const endAt = slotPositions(endSize, small + gap + medium + gap, gap)

  return Array.from({ length: count }, (_, index) => {
    const slot = index - position
    const start = interpolate(startSize, startAt, slot)
    const end = interpolate(endSize, endAt, slot)

    return { x: lerp(start.x, end.x, t), size: lerp(start.size, end.size, t) }
  })
}

/** Where each slot starts, slot 0 at `origin`, from the sizes of the slots between. */
function slotPositions(size: (slot: number) => number, origin: number, gap: number) {
  return (slot: number) => {
    let x = origin
    for (let between = 0; between < slot; between += 1) x += size(between) + gap
    for (let between = -1; between >= slot; between -= 1) x -= size(between) + gap
    return x
  }
}

/** A slot between two whole ones, its size and place eased from one to the other. */
function interpolate(size: (slot: number) => number, at: (slot: number) => number, slot: number) {
  const whole = Math.floor(slot)
  const part = slot - whole

  return {
    x: lerp(at(whole), at(whole + 1), part),
    size: lerp(size(whole), size(whole + 1), part),
  }
}

const lerp = (from: number, to: number, part: number) => from + (to - from) * part
const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

/**
 * Sets the carousels on the page going: each `[data-carousel]` with its track of items
 * (`[data-carousel-track]`, its `<li>` each holding one link), its paging buttons
 * (`[data-carousel-step]`, hidden in `[data-carousel-nav]` while all fits) and its
 * button showing every item at once (`[data-carousel-all]`).
 */
export function initializeCarousels(root: ParentNode = document) {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)')

  for (const carousel of root.querySelectorAll<HTMLElement>('[data-carousel]')) {
    const track = carousel.querySelector<HTMLElement>('[data-carousel-track]')
    const nav = carousel.querySelector<HTMLElement>('[data-carousel-nav]')
    const all = carousel.querySelector<HTMLButtonElement>('[data-carousel-all]')
    if (!track) continue

    const items = [...track.children] as HTMLElement[]
    const links = items.map((item) => item.querySelector<HTMLElement>('a')!)
    const [previous, next] = nav?.querySelectorAll<HTMLButtonElement>('[data-carousel-step]') ?? []
    let keylines: Keylines | undefined
    let frame = 0

    const multiBrowse = () => !reduce.matches && !carousel.classList.contains('is-expanded')

    // Sizes the items' boxes so the row scrolls exactly as far as it turns round.
    const measure = () => {
      if (!multiBrowse()) {
        keylines = undefined
        carousel.classList.remove('is-multi-browse')
        for (const item of items) item.style.width = ''
        for (const link of links) link.removeAttribute('style')
        return
      }

      keylines = getKeylines(track.clientWidth)
      const steps = getSteps(items.length, keylines)
      const rest = track.clientWidth / Math.max(1, items.length - steps)
      carousel.classList.add('is-multi-browse')
      carousel.style.setProperty('--large', `${keylines.large}px`)
      items.forEach((item, index) => {
        item.style.width = `${index < steps ? keylines!.stride : rest}px`
      })
      draw()
    }

    const draw = () => {
      frame = 0
      if (!keylines) return
      const position = track.scrollLeft / keylines.stride
      const placed = placeItems(items.length, keylines, position)
      const { large, medium } = keylines

      placed.forEach(({ x, size }, index) => {
        const link = links[index]!
        const inset = (large - size) / 2
        const left = items[index]!.offsetLeft - track.scrollLeft
        link.style.translate = `${x - left - inset}px 0`
        link.style.clipPath = `inset(0 ${inset}px round 0.75rem)`
        link.style.setProperty('--inset', `${inset}px`)
        // The words show on the large items, fading out by the time one is medium.
        link.style.setProperty('--words', String(clamp((size - medium) / (large - medium), 0, 1)))
      })
    }

    const update = () => {
      const end = track.scrollWidth - track.clientWidth
      if (nav) nav.hidden = end <= 1 || carousel.classList.contains('is-expanded')
      if (previous) previous.disabled = track.scrollLeft <= 1
      if (next) next.disabled = track.scrollLeft >= end - 1
      if (keylines && !frame) frame = requestAnimationFrame(draw)
    }

    const scrollToItem = (index: number) => {
      if (!keylines) return
      const steps = getSteps(items.length, keylines)
      const position = track.scrollLeft / keylines.stride
      // Far enough that the item is one of the large ones.
      const target =
        index < position
          ? index
          : index > position + keylines.largeCount - 1
            ? index - keylines.largeCount + 1
            : position
      track.scrollTo({
        left: clamp(Math.round(target), 0, steps) * keylines.stride,
        behavior: reduce.matches ? 'auto' : 'smooth',
      })
    }

    nav?.addEventListener('click', (event) => {
      const button = (event.target as Element).closest<HTMLButtonElement>('[data-carousel-step]')
      if (!button) return
      const step = Number(button.dataset.carouselStep)
      const page = keylines ? keylines.largeCount * keylines.stride : track.clientWidth
      track.scrollBy({ left: step * page, behavior: reduce.matches ? 'auto' : 'smooth' })
    })

    all?.addEventListener('click', () => {
      const expanded = carousel.classList.toggle('is-expanded')
      all.setAttribute('aria-expanded', String(expanded))
      track.scrollLeft = 0
      measure()
      update()
      // Folded back up, the row may have left the screen above it.
      if (!expanded) carousel.scrollIntoView({ block: 'nearest' })
    })

    // Left and right move between the items; one focused scrolls to where it shows large.
    track.addEventListener('keydown', (event) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
      const index = links.indexOf(document.activeElement as HTMLElement)
      const target = links[index + (event.key === 'ArrowRight' ? 1 : -1)]
      if (index < 0 || !target) return
      event.preventDefault()
      target.focus({ preventScroll: true })
    })
    track.addEventListener('focusin', (event) => {
      const index = links.indexOf(event.target as HTMLElement)
      if (index >= 0) scrollToItem(index)
    })

    track.addEventListener('scroll', update, { passive: true })
    reduce.addEventListener('change', () => {
      measure()
      update()
    })
    new ResizeObserver(() => {
      measure()
      update()
    }).observe(track)
    measure()
    update()
  }
}
