import type { APIRoute } from 'astro'
import sharp from 'sharp'
import {
  getMomentDateLabel,
  getMomentExcerpt,
  getMomentPagePaths,
  MOMENT_OG_IMAGES,
  type MomentPagePathProps,
} from '@/moments'
import { cachedCard, renderOgImage } from '@/og-image'
import { getCardPhotos } from '@/page-cards'

export const getStaticPaths = async () => (MOMENT_OG_IMAGES ? getMomentPagePaths() : [])

// A Moment's share card: its words, when and where, and its first photos or posters beside them.
export const GET: APIRoute<MomentPagePathProps> = async ({ props: { moment } }) => {
  const photos = getCardPhotos([moment])
  const excerpt = getMomentExcerpt(moment, 120)
  const dateLabel = getMomentDateLabel(moment)
  const options = { title: excerpt || dateLabel, date: excerpt ? dateLabel : undefined, photos }
  const image = await cachedCard('moment.jpg', options, async () =>
    sharp(await renderOgImage(options))
      .jpeg({ mozjpeg: true, quality: 82 })
      .toBuffer(),
  )

  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/jpeg' } })
}
