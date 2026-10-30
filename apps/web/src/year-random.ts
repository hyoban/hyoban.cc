// A year review shows a different hand of its photos each visit: a picture that has others
// to choose from names them in data-pick, and a set of pictures shares one list in
// data-pick-set, dealt without repeats. A visit is a place in the history, not a load: a reload or a return
// deals the same hand again, from the seed kept in the entry's state, so what was on screen
// is still there. The share pictures draw the page again in a frame
// (./year-share-pictures.ts), which takes this visit's seed so it deals the same hand.

/** The query parameter a frame of the page takes the seed from. */
export const SEED_PARAMETER = 'seed'
/** The key in `history.state` a visit's seed is kept under. */
export const SEED_STATE_KEY = 'yearSeed'

/**
 * Deals the page's hand. The page begins it inline (`'start'`) before its first picture is
 * read, each picture then picked as the page reads it, so it loads the photo it shows from
 * the start, as early as one with nothing to pick; and ends it inline (`'finish'`) once
 * the page is read, before it's first drawn, dealing the sets. Called with
 * neither as the page loads, it deals all at once a page the router swapped in, its inline
 * scripts not run, and leaves one dealt already alone. Either way the pictures are picked
 * in the page's order, then the sets, so a seed deals one hand. The seed is
 * the frame's, else the visit's, else a new one, kept on the element marked
 * data-year-deal, which goes with the page. Self-contained, constants and all, as the
 * page writes it out from its source.
 */
export function dealYear(stage?: 'start' | 'finish') {
  type Deal = { seed: number; state: number; picked: WeakSet<Element>; observer?: MutationObserver }
  const key = Symbol.for('hyoban.year-deal')
  const scope = window as unknown as Record<symbol, Deal | undefined>
  const marker = () => document.querySelector<HTMLElement>('[data-year-deal]')

  if (stage !== 'finish') {
    if (!stage) {
      const page = marker()
      if (!page || page.dataset.yearSeed) return
    }
    const isSeed = (value: number) => Number.isInteger(value) && value > 0
    const given = Number(new URLSearchParams(location.search).get('seed'))
    const kept = Number(history.state?.yearSeed)
    const seed = isSeed(given)
      ? given
      : isSeed(kept)
        ? kept
        : Math.floor(Math.random() * 2 ** 31) + 1
    scope[key] = { seed, state: seed >>> 0, picked: new WeakSet() }
  }
  const deal = scope[key]
  if (!deal) return

  // mulberry32: the same seed, the same numbers.
  const next = () => {
    deal.state = (deal.state + 0x6d2b79f5) >>> 0
    let value = deal.state
    value = Math.imul(value ^ (value >>> 15), value | 1)
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61)
    return ((value ^ (value >>> 14)) >>> 0) / 2 ** 32
  }
  const shuffle = <T>(items: T[]) => {
    for (let index = items.length - 1; index > 0; index--) {
      const other = Math.floor(next() * (index + 1))
      ;[items[index], items[other]] = [items[other]!, items[index]!]
    }
    return items
  }
  const read = (value: string | undefined) => {
    try {
      return JSON.parse(value ?? '[]') as string[]
    } catch {
      return []
    }
  }
  const pick = (image: HTMLImageElement) => {
    if (deal.picked.has(image)) return
    deal.picked.add(image)
    const photos = read(image.dataset.pick)
    const photo = photos[Math.floor(next() * photos.length)]
    if (photo && photo !== image.getAttribute('src')) image.src = photo
  }
  const pickWithin = (node: Node) => {
    if (!(node instanceof Element)) return
    if (node.matches('img[data-pick]')) pick(node as HTMLImageElement)
    node.querySelectorAll<HTMLImageElement>('img[data-pick]').forEach(pick)
  }

  if (stage === 'start') {
    deal.observer = new MutationObserver((records) => {
      for (const record of records) record.addedNodes.forEach(pickWithin)
    })
    deal.observer.observe(document.documentElement, { childList: true, subtree: true })
    return
  }

  if (deal.observer) {
    for (const record of deal.observer.takeRecords()) record.addedNodes.forEach(pickWithin)
    deal.observer.disconnect()
  }
  document.querySelectorAll<HTMLImageElement>('img[data-pick]').forEach(pick)

  for (const set of document.querySelectorAll<HTMLElement>('[data-pick-set]')) {
    const photos = shuffle(read(set.dataset.pickSet))
    set.querySelectorAll('img').forEach((image, index) => {
      const photo = photos[index]
      if (photo && photo !== image.getAttribute('src')) image.src = photo
    })
  }

  const dealt = marker()
  if (dealt) dealt.dataset.yearSeed = String(deal.seed)
  delete scope[key]
}

/** This visit's seed, as dealt. */
export function getSeed() {
  return Number(document.querySelector<HTMLElement>('[data-year-deal]')?.dataset.yearSeed)
}

/**
 * Keeps the visit's seed in its history entry, once the router has made the entry its own,
 * for a reload or a return to deal the same hand.
 */
export function keepSeed() {
  const seed = getSeed()
  if (history.state && seed && history.state[SEED_STATE_KEY] !== seed) {
    history.replaceState({ ...history.state, [SEED_STATE_KEY]: seed }, '')
  }
}
