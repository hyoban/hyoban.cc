// The search overlay's index and how it is searched. The build writes the entries to
// /search.json (src/pages/search.json.ts); the overlay fetches them and matches in the
// browser, so this module stays free of anything only the build has.

export type SearchPhoto = {
  alt: string
  src: string
  width: number
  height: number
}

export type SearchEntry = {
  kind: 'moment' | 'post'
  url: string
  /** `YYYY-MM-DD`, what the results are ordered by, newest first. */
  date: string
  /** A post's title. */
  title?: string
  /** A Moment's words, or a post's prose without its markup. */
  text: string
  /** Where a Moment was. */
  place?: string
  /** A Moment's photos and video posters that have alt text. */
  photos?: SearchPhoto[]
}

export type SearchResult = {
  entry: SearchEntry
  /** The terms found in the title, the words and the place, for marking. */
  terms: string[]
  /** Whether the words themselves, not just a title, place or photo, hold a term. */
  inText: boolean
  /** The photos whose alt text holds a term. */
  photos: SearchPhoto[]
}

/**
 * `text` in lowercase with full-width letters and digits made plain, a character for a
 * character, so an index into it is one into `text`: NFKC alone turns `…` into `...`.
 */
const normalize = (text: string) =>
  Array.from(text, (character) => {
    const plain = character.normalize('NFKC').toLowerCase()
    return plain.length === character.length ? plain : character
  }).join('')

/** The query's terms, split at spaces: `月亮 2026` finds what holds both. */
export function getSearchTerms(query: string) {
  return [...new Set(normalize(query).split(/\s+/).filter(Boolean))]
}

/**
 * The entries that hold every term somewhere, in their title, words, place or photos'
 * alt text, newest first. Chinese has no spaces between words, so a term is matched as a
 * plain substring rather than a word.
 */
export function searchEntries(entries: SearchEntry[], query: string): SearchResult[] {
  const terms = getSearchTerms(query)
  if (terms.length === 0) return []

  return entries
    .flatMap((entry) => {
      const words = normalize([entry.title, entry.text, entry.place].filter(Boolean).join('\n'))
      const alts = (entry.photos ?? []).map((photo) => normalize(photo.alt))
      const found = terms.every(
        (term) => words.includes(term) || alts.some((alt) => alt.includes(term)),
      )
      if (!found) return []
      return [
        {
          entry,
          terms,
          inText: terms.some((term) => normalize(entry.text).includes(term)),
          photos: (entry.photos ?? []).filter((_, index) =>
            terms.some((term) => alts[index]!.includes(term)),
          ),
        },
      ]
    })
    .sort((a, b) => b.entry.date.localeCompare(a.entry.date))
}

/**
 * Up to `length` characters of `text` around the first term it holds, with an ellipsis
 * on a side that was cut; its start when it holds none.
 */
export function getSnippet(text: string, terms: string[], length = 80) {
  const flat = text.replace(/\s+/g, ' ').trim()
  const lower = normalize(flat)
  const hits = terms.map((term) => lower.indexOf(term)).filter((index) => index >= 0)
  const first = hits.length > 0 ? Math.min(...hits) : 0
  const start = Math.max(0, Math.min(first - Math.floor(length / 4), flat.length - length))
  const end = Math.min(flat.length, start + length)
  return `${start > 0 ? '…' : ''}${flat.slice(start, end)}${end < flat.length ? '…' : ''}`
}

/** `text` cut into runs, those that are one of the terms marked, for <mark>. */
export function markTerms(text: string, terms: string[]) {
  const lower = normalize(text)
  const runs: Array<{ text: string; marked: boolean }> = []
  let index = 0
  let plain = 0
  while (index < text.length) {
    const term = terms
      .filter((candidate) => lower.startsWith(candidate, index))
      .sort((a, b) => b.length - a.length)[0]
    if (!term) {
      index += 1
      continue
    }
    if (index > plain) runs.push({ text: text.slice(plain, index), marked: false })
    runs.push({ text: text.slice(index, index + term.length), marked: true })
    index += term.length
    plain = index
  }
  if (plain < text.length) runs.push({ text: text.slice(plain), marked: false })
  return runs
}
