// A year review shows a different hand of its photos each visit: a picture that has others
// to choose from names them in data-pick, and a set of pictures shares one list in
// data-pick-set, dealt without repeats; a list whose order says nothing, data-shuffle,
// comes in a new order. All of it before the pictures load, as they wait to be scrolled
// to, so none is fetched twice. The share pictures draw the page again in a frame
// (./year-share-pictures.ts), which takes this visit's seed so it deals the same hand.

/** The query parameter a frame of the page takes the seed from. */
export const SEED_PARAMETER = 'seed'

/** A seeded random number generator, mulberry32: the same seed, the same numbers. */
function random(seed: number) {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let value = state
    value = Math.imul(value ^ (value >>> 15), value | 1)
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61)
    return ((value ^ (value >>> 14)) >>> 0) / 2 ** 32
  }
}

function shuffle<T>(items: T[], next: () => number) {
  for (let index = items.length - 1; index > 0; index--) {
    const other = Math.floor(next() * (index + 1))
    ;[items[index], items[other]] = [items[other]!, items[index]!]
  }
  return items
}

const read = (element: HTMLElement, name: 'pick' | 'pickSet') => {
  try {
    return JSON.parse(element.dataset[name] ?? '[]') as string[]
  } catch {
    return []
  }
}

/** This visit's seed: the one a frame was given, else a new one, kept on the page. */
export function getSeed() {
  const root = document.documentElement
  const given = Number(new URLSearchParams(location.search).get(SEED_PARAMETER))
  root.dataset.yearSeed ??= String(
    Number.isInteger(given) && given > 0 ? given : Math.floor(Math.random() * 2 ** 31) + 1,
  )
  return Number(root.dataset.yearSeed)
}

export function dealYear() {
  const next = random(getSeed())

  for (const image of document.querySelectorAll<HTMLImageElement>('img[data-pick]')) {
    const photos = read(image, 'pick')
    const photo = photos[Math.floor(next() * photos.length)]
    if (photo && photo !== image.getAttribute('src')) image.src = photo
  }

  for (const set of document.querySelectorAll<HTMLElement>('[data-pick-set]')) {
    const photos = shuffle(read(set, 'pickSet'), next)
    set.querySelectorAll('img').forEach((image, index) => {
      const photo = photos[index]
      if (photo && photo !== image.getAttribute('src')) image.src = photo
    })
  }

  for (const list of document.querySelectorAll<HTMLElement>('[data-shuffle]')) {
    const items = shuffle([...list.children] as HTMLElement[], next)
    items.forEach((item, index) => {
      item.style.setProperty('--order', String(index))
      const link = item.querySelector('[aria-label]')
      const label = link?.getAttribute('aria-label')
      if (link && label) {
        link.setAttribute('aria-label', label.replace(/第 \d+ 个/, `第 ${index + 1} 个`))
      }
    })
    list.append(...items)
  }
}
