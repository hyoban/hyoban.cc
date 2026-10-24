// Material 3's multi-browse carousel for the year's places: the items in a row take a
// large, a medium or a small size by where they are, so a few show whole and the next
// peek in behind them, and scrolling grows each as it comes in and shrinks it as it goes.
// Items keep one size in the page, so the scroller snaps to them; each is drawn moved
// and masked to its place, its photo staying centred under the mask. At the start the
// large items lead, [L, L, M, S]; over the last steps the row turns round to end on
// them, [S, M, L, L]. With reduced motion the row stays a plain one of same-sized items.

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

/**
 * The carousel's geometry, and a row laid out and drawn by it. Self-contained, constants
 * and all, so the page can write it out from its source and lay its rows out as soon as
 * they're read, before they're first drawn: else they show as a plain row and change
 * size when the script below takes them over, once the page has loaded. The script then
 * goes on from the same numbers.
 */
export function carouselGeometry() {
  const TARGET_LARGE = 176
  /** Material's small items are 40–56dp. */
  const SMALL = 48
  const GAP = 8
  const MEDIUM_RATIO = 0.6
  /** How many steps of scrolling the row takes to turn round at its end. */
  const TURN = 2

  const lerp = (from: number, to: number, part: number) => from + (to - from) * part
  const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))

  /** The sizes that fill `width` with large items, then a medium and a small one. */
  function getKeylines(width: number): Keylines {
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
  function getSteps(count: number, keylines: Keylines) {
    return Math.max(0, count - keylines.largeCount)
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

  /** Each item's left edge in the row and its size, scrolled `position` items along. */
  function placeItems(count: number, keylines: Keylines, position: number) {
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

  /**
   * Sizes a row's items so it scrolls exactly as far as it turns round, the large size
   * given to every card; their left edges, read once their widths are set.
   */
  function sizeRow(carousel: HTMLElement, track: HTMLElement, items: HTMLElement[]) {
    const keylines = getKeylines(track.clientWidth)
    const steps = getSteps(items.length, keylines)
    const rest = track.clientWidth / Math.max(1, items.length - steps)
    carousel.classList.add('is-multi-browse')
    carousel.style.setProperty('--large', `${keylines.large}px`)
    items.forEach((item, index) => {
      item.style.width = `${index < steps ? keylines.stride : rest}px`
    })
    return { keylines, offsets: items.map((item) => item.offsetLeft) }
  }

  /**
   * How each card is drawn, scrolled `scrollLeft` along: moved and masked to its place,
   * its words fading out by the time it is medium; hidden out of sight, unless `shown`.
   */
  function lookItems(
    keylines: Keylines,
    offsets: number[],
    scrollLeft: number,
    width: number,
    shown: boolean,
  ) {
    const { large, medium } = keylines
    return placeItems(offsets.length, keylines, scrollLeft / keylines.stride).map(
      ({ x, size }, index) => {
        const inset = (large - size) / 2
        return {
          hidden: !shown && (x + size <= 0 || x >= width),
          translate: `${(x - offsets[index]! + scrollLeft - inset).toFixed(1)}px 0`,
          clip: `${inset.toFixed(1)}px`,
          words: clamp((size - medium) / (large - medium), 0, 1).toFixed(3),
        }
      },
    )
  }

  type Look = ReturnType<typeof lookItems>[number]

  /**
   * Writes a card's look, only what differs from what it was `last` given: most of a row
   * sits out of sight, and every style written costs a restyle and a relayer.
   */
  function drawItem(link: HTMLElement, look: Look, last: Partial<Look> = {}) {
    if (look.hidden !== last.hidden) {
      last.hidden = look.hidden
      link.style.visibility = look.hidden ? 'hidden' : ''
    }
    if (look.hidden) return
    if (look.translate !== last.translate) link.style.translate = last.translate = look.translate
    if (look.clip !== last.clip) {
      link.style.clipPath = `inset(0 ${look.clip} round 0.75rem)`
      link.style.setProperty('--inset', (last.clip = look.clip))
    }
    if (look.words !== last.words) link.style.setProperty('--words', (last.words = look.words))
  }

  /** The row's paging buttons, shown while it has more than fits, each while it can go. */
  function setPaging(carousel: HTMLElement, track: HTMLElement) {
    const nav = carousel.querySelector<HTMLElement>('[data-carousel-nav]')
    const [previous, next] = nav?.querySelectorAll<HTMLButtonElement>('[data-carousel-step]') ?? []
    const end = track.scrollWidth - track.clientWidth
    if (nav) nav.hidden = end <= 1 || carousel.classList.contains('is-expanded')
    if (previous) previous.disabled = track.scrollLeft <= 1
    if (next) next.disabled = track.scrollLeft >= end - 1
  }

  /** Every row on the page laid out at its start, unless motion is reduced, and paged. */
  function layOutRows() {
    const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches
    for (const carousel of document.querySelectorAll<HTMLElement>('[data-carousel]')) {
      const track = carousel.querySelector<HTMLElement>('[data-carousel-track]')
      if (!track) continue
      if (!reduce && !carousel.classList.contains('is-expanded')) {
        const items = [...track.children] as HTMLElement[]
        const { keylines, offsets } = sizeRow(carousel, track, items)
        lookItems(keylines, offsets, track.scrollLeft, track.clientWidth, false).forEach(
          (look, index) => drawItem(items[index]!.querySelector('a')!, look),
        )
      }
      setPaging(carousel, track)
    }
  }

  return {
    clamp,
    getKeylines,
    getSteps,
    placeItems,
    sizeRow,
    lookItems,
    drawItem,
    setPaging,
    layOutRows,
  }
}

const geometry = carouselGeometry()
const { clamp, sizeRow, lookItems, drawItem, setPaging } = geometry
export const { getKeylines, getSteps, placeItems } = geometry

/**
 * Sets the carousels on the page going: each `[data-carousel]` with its track of items
 * (`[data-carousel-track]`, its `<li>` each holding one link), its paging buttons
 * (`[data-carousel-step]`, hidden in `[data-carousel-nav]` while all fits) and its
 * button showing every item at once (`[data-carousel-all]`).
 */
export function initializeCarousels() {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)')

  for (const carousel of document.querySelectorAll<HTMLElement>('[data-carousel]')) {
    const track = carousel.querySelector<HTMLElement>('[data-carousel-track]')
    const nav = carousel.querySelector<HTMLElement>('[data-carousel-nav]')
    const all = carousel.querySelector<HTMLButtonElement>('[data-carousel-all]')
    if (!track) continue

    const items = [...track.children] as HTMLElement[]
    const links = items.map((item) => item.querySelector<HTMLElement>('a')!)
    let keylines: Keylines | undefined
    /** Each item's left edge in the track, read once its width is set, not every frame. */
    let offsets: number[] = []
    let frame = 0

    // What each link was last given, so a frame writes only what changed.
    let drawn = links.map(() => ({}))
    // With focus in the row every item stays shown, so Tab and the arrows reach them all.
    let focused = false

    const multiBrowse = () => !reduce.matches && !carousel.classList.contains('is-expanded')

    // Sizes the items' boxes so the row scrolls exactly as far as it turns round.
    const measure = () => {
      drawn = links.map(() => ({}))
      if (!multiBrowse()) {
        keylines = undefined
        carousel.classList.remove('is-multi-browse')
        for (const item of items) item.style.width = ''
        for (const link of links) link.removeAttribute('style')
        return
      }

      ;({ keylines, offsets } = sizeRow(carousel, track, items))
      draw()
    }

    const draw = () => {
      frame = 0
      if (!keylines) return
      lookItems(keylines, offsets, track.scrollLeft, track.clientWidth, focused).forEach(
        (look, index) => drawItem(links[index]!, look, drawn[index]),
      )
    }

    const update = () => {
      setPaging(carousel, track)
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
      if (!focused) {
        focused = true
        draw()
      }
      const index = links.indexOf(event.target as HTMLElement)
      if (index >= 0) scrollToItem(index)
    })
    track.addEventListener('focusout', (event) => {
      if (track.contains(event.relatedTarget as Node | null)) return
      focused = false
      draw()
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
