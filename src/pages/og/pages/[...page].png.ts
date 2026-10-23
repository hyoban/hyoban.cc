import type { APIRoute } from 'astro'
import { createHash } from 'node:crypto'
import { getMapCardBackground } from '@/map-card'
import { cachedCard, pngResponse, renderOgImage } from '@/og-image'
import { getPageCards, type PageCard } from '@/page-cards'

export async function getStaticPaths() {
  // HTML-only integration builds do not need to fetch fonts and maps for these cards.
  if (process.env.SKIP_PAGE_OG_IMAGES === '1') return []
  return (await getPageCards()).map(({ card, path }) => ({
    params: { page: path.replace(/^\/+|\/+$/g, '') },
    props: { card },
  }))
}

export const GET: APIRoute<{ card: PageCard }> = async ({ props: { card } }) => {
  const { map, ...options } = card
  // A map page's card draws on its map, where the build can draw maps.
  const background = map ? await getMapCardBackground(map) : undefined
  const inputs = {
    ...options,
    background: background && createHash('sha256').update(background).digest('hex'),
  }
  return pngResponse(
    await cachedCard('page.png', inputs, () => renderOgImage({ ...options, background })),
  )
}
