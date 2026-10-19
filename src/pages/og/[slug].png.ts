import type { APIRoute } from 'astro'
import { pngResponse, renderOgImage } from '@/og-image'
import { formatPostFullDate, getMarkdownEntries, type MarkdownEntry } from '@/utils'

export async function getStaticPaths() {
  return (await getMarkdownEntries()).map((entry) => ({
    params: { slug: entry.data.link },
    props: { entry },
  }))
}

export const GET: APIRoute<{ entry: MarkdownEntry }> = async ({ props: { entry } }) =>
  pngResponse(
    await renderOgImage({
      title: entry.data.title,
      description: entry.data.description,
      date: entry.collection === 'posts' ? formatPostFullDate(entry.data.pubDate) : undefined,
    }),
  )
