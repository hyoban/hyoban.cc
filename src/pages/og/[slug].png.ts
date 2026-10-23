import type { APIRoute } from 'astro'
import { cachedCard, pngResponse, renderOgImage } from '@/og-image'
import { formatPostFullDate, getMarkdownEntries, type MarkdownEntry } from '@/utils'

export async function getStaticPaths() {
  return (await getMarkdownEntries()).map((entry) => ({
    params: { slug: entry.data.link },
    props: { entry },
  }))
}

export const GET: APIRoute<{ entry: MarkdownEntry }> = async ({ props: { entry } }) => {
  const options = {
    title: entry.data.title,
    description: entry.data.ogDescription ?? entry.data.description,
    photos: entry.data.ogImage ? [entry.data.ogImage] : undefined,
    date: entry.collection === 'posts' ? formatPostFullDate(entry.data.pubDate) : undefined,
  }
  return pngResponse(await cachedCard('post.png', options, () => renderOgImage(options)))
}
