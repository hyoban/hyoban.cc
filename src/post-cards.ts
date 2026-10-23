import type { APIRoute } from 'astro'
import {
  cachedCard,
  cardResponse,
  encodeCard,
  getCardFormat,
  renderOgImage,
  type CardFormat,
} from '@/og-image'
import { formatPostFullDate, getMarkdownEntries, type MarkdownEntry } from '@/utils'

// The share cards of the posts and the pages written in markdown: /og/<link>.jpg for one
// with a cover photo, /og/<link>.png for one of words alone.

function getPostCard(entry: MarkdownEntry) {
  return {
    title: entry.data.title,
    description: entry.data.ogDescription ?? entry.data.description,
    photos: entry.data.ogImage ? [entry.data.ogImage] : undefined,
    date: entry.collection === 'posts' ? formatPostFullDate(entry.data.pubDate) : undefined,
  }
}

export function getPostCardPath(entry: MarkdownEntry) {
  return `/og/${entry.data.link}.${getCardFormat(getPostCard(entry))}`
}

/** The route of the cards in `format`, which /og/[slug].png.ts and /og/[slug].jpg.ts export. */
export function postCardRoute(format: CardFormat) {
  const getStaticPaths = async () =>
    (await getMarkdownEntries())
      .filter((entry) => getCardFormat(getPostCard(entry)) === format)
      .map((entry) => ({ params: { slug: entry.data.link }, props: { entry } }))
  const GET: APIRoute<{ entry: MarkdownEntry }> = async ({ props: { entry } }) => {
    const options = getPostCard(entry)
    return cardResponse(
      await cachedCard(`post.${format}`, options, async () =>
        encodeCard(await renderOgImage(options), format),
      ),
      format,
    )
  }
  return { getStaticPaths, GET }
}
