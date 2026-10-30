import {
  getCalendarDateKey,
  getMomentPagePath,
  getMoments,
  type ResolvedMomentMedia,
} from '@/moments'
import { searchEntries, type SearchEntry, type SearchPhoto } from '@/search'
import { getSortedPosts } from '@/utils'

// What the search overlay (src/components/SearchDialog.astro) searches, as /search.json
// (src/pages/search.json.ts) gives it: every Moment the site shows,
// with its places and its photos' alt text, and every post's title and prose.

/** A photo's smallest size that still fills a thumbnail; a video's poster. */
function getPhoto(media: ResolvedMomentMedia): SearchPhoto | undefined {
  const image = media.type === 'video' ? media.poster : media.src
  if (!media.alt || !image) return undefined
  const source = image.sources.find((candidate) => candidate.width >= 240) ?? image.sources.at(-1)
  return { alt: media.alt, src: source?.src ?? image.src, width: image.width, height: image.height }
}

/** A post's prose without its code, images, link targets, HTML and markup. */
function getProse(markdown: string) {
  return markdown
    .replaceAll(/^(`{3,}|~{3,})[^\n]*\n[\s\S]*?^\1[^\S\n]*$/gm, '')
    .replaceAll(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replaceAll(/\]\([^)]*\)/g, ']')
    .replaceAll(/<[^>]+>/g, '')
    .replaceAll(/^\s*(?:#+|>|[-*+]|\d+\.)\s+/gm, '')
    .replaceAll(/[*_`~[\]]/g, '')
    .replaceAll(/\s+/g, ' ')
    .trim()
}

let entries: Promise<SearchEntry[]> | undefined

export function getSearchEntries() {
  entries ??= loadSearchEntries()
  return entries
}

async function loadSearchEntries() {
  const moments = (await getMoments()).map((moment): SearchEntry => {
    const photos = moment.media.flatMap((media) => getPhoto(media) ?? [])
    const place = moment.locations.map((location) => location.name).join(' ')
    return {
      kind: 'moment',
      url: getMomentPagePath(moment),
      date: moment.dateKey,
      text: moment.text,
      ...(place ? { place } : {}),
      ...(photos.length > 0 ? { photos } : {}),
    }
  })
  const posts = (await getSortedPosts()).map((post): SearchEntry => ({
    kind: 'post',
    url: `/${post.data.link}/`,
    date: getCalendarDateKey(post.data.pubDate),
    title: post.data.title,
    text: getProse(post.body ?? ''),
  }))
  return [...moments, ...posts]
}

/** The words the empty search overlay offers, picked by hand from what the Moments are about. */
const HOT_WORDS = [
  '自驾',
  '露营',
  '床车',
  '充电',
  '海边',
  '雪山',
  '日出',
  '晚霞',
  '月亮',
  '古镇',
  '烧烤',
  '咖啡',
  '骑行',
  '相机',
  'GitHub',
]

/** The hot words with how many entries each finds; one that finds none fails the build. */
export async function getHotWords() {
  const all = await getSearchEntries()
  return HOT_WORDS.map((word) => {
    const count = searchEntries(all, word).length
    if (count === 0) throw new Error(`The search hot word "${word}" finds nothing.`)
    return { word, count }
  })
}
