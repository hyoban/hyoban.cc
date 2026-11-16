import { pinyin } from 'pinyin-pro'

// A trip's address on the site reads as its title: its year and the title's words in pinyin,
// `2026-xuzhou-zhoumo-xing` for 徐州周末行 of 2026, which the site also serves as
// /trip/2026/xuzhou-zhoumo-xing/. It is worked out at each build, so a renamed trip moves
// address; its key in the data (a slug, or the uid of one made on the phone) never does, and
// every address it has had by that key leads to the new one.

const words = new Intl.Segmenter('zh', { granularity: 'word' })

/**
 * A title's words in pinyin, without tones, joined by hyphens: the words as the system's
 * dictionary finds them, and a run of characters it leaves single read two at a time, as a
 * pair is most often a word it doesn't know (自驾, 床车). Letters and figures stay as they are.
 */
export function getTitleSlug(title: string) {
  const found: string[] = []
  let singles: string[] = []
  const flush = () => {
    for (let index = 0; index < singles.length; index += 2) {
      found.push(singles.slice(index, index + 2).join(''))
    }
    singles = []
  }

  for (const { isWordLike, segment } of words.segment(title)) {
    if (!isWordLike) {
      flush()
      continue
    }

    if (/^\p{Script=Han}$/u.test(segment)) {
      singles.push(segment)
      continue
    }

    flush()
    found.push(segment)
  }

  flush()

  return found
    .map((word) => pinyin(word, { toneType: 'none', type: 'array', v: true }).join(''))
    .join('-')
    .toLowerCase()
    .replaceAll(/[^a-z0-9]+/g, '-')
    .replaceAll(/^-+|-+$/g, '')
}

/**
 * Each trip's readable id by its key: its year, then its title's slug, the earlier of two
 * trips the same in both keeping it and the later taking `-2`, `-3`; a title with nothing to
 * read keeps its key.
 */
export function getReadableTripIds(
  trips: readonly { key: string; start: string; title: string }[],
) {
  const ids = new Map<string, string>()
  const taken = new Set<string>()

  for (const trip of trips.toSorted(
    (first, second) =>
      first.start.localeCompare(second.start) || first.key.localeCompare(second.key),
  )) {
    const slug = getTitleSlug(trip.title)
    const base = slug ? `${trip.start.slice(0, 4)}-${slug}` : trip.key
    let id = base

    for (let count = 2; taken.has(id); count += 1) {
      id = `${base}-${count}`
    }

    taken.add(id)
    ids.set(trip.key, id)
  }

  return ids
}
