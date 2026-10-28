// The search overlay's (src/components/SearchDialog.astro) index, fetched once, and the
// pieces a result is drawn from.

import { getSnippet, markTerms, type SearchEntry, type SearchResult } from '@/search'

let entries: Promise<SearchEntry[]> | undefined

/** /search.json, fetched the first time it's needed; a failed fetch is tried again next time. */
export function loadSearchEntries() {
  entries ??= fetch('/search.json').then((response) => {
    if (!response.ok) throw new Error(`/search.json answered ${response.status}`)
    return response.json() as Promise<SearchEntry[]>
  })
  entries.catch(() => (entries = undefined))
  return entries
}

export function formatSearchDate(date: string) {
  const [year, month, day] = date.split('-').map(Number)
  return `${year} 年 ${month} 月 ${day} 日`
}

export function element<K extends keyof HTMLElementTagNameMap>(tag: K, className = '') {
  return Object.assign(document.createElement(tag), { className })
}

/** `text` appended to `parent`, its terms in <mark>. */
export function appendMarked(parent: HTMLElement, text: string, terms: string[]) {
  for (const run of markTerms(text, terms)) {
    parent.append(run.marked ? Object.assign(element('mark'), { textContent: run.text }) : run.text)
  }
}

/** Where an entry is from: a Moment's place, or that it's a post. */
export function getSearchPlace(result: SearchResult) {
  return result.entry.kind === 'post' ? '文章' : result.entry.place
}

/** The words around a term; the matching photos' alt text when only a photo holds one. */
export function getSearchWords({ entry, terms, inText, photos }: SearchResult, length?: number) {
  return inText || photos.length === 0
    ? getSnippet(entry.text, terms, length)
    : photos.map((photo) => photo.alt).join(' ')
}

export function createSearchImage({ src, alt, width, height }: SearchResult['photos'][number]) {
  return Object.assign(element('img'), {
    src,
    alt,
    width,
    height,
    loading: 'lazy',
    decoding: 'async',
  } satisfies Partial<HTMLImageElement>)
}
