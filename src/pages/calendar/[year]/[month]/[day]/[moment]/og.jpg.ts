import type { APIRoute } from 'astro'
import {
  getMomentDateLabel,
  getMomentExcerpt,
  getMomentPagePaths,
  MOMENT_OG_IMAGES,
  type MomentPagePathProps,
} from '@/moments'
import { cachedCard, cardResponse, encodeCard, renderOgImage } from '@/og-image'
import { getCardPhotos } from '@/card-data'

export const getStaticPaths = async () => (MOMENT_OG_IMAGES ? getMomentPagePaths() : [])

// A Moment's share card: its words, when and where, and its first photos or posters beside them.
export const GET: APIRoute<MomentPagePathProps> = async ({ props: { moment } }) => {
  const photos = getCardPhotos([moment])
  const excerpt = getMomentExcerpt(moment, 120)
  const dateLabel = getMomentDateLabel(moment)
  const options = { title: excerpt || dateLabel, date: excerpt ? dateLabel : undefined, photos }
  const image = await cachedCard('moment.jpg', options, async () =>
    encodeCard(await renderOgImage(options), 'jpg'),
  )

  return cardResponse(image, 'jpg')
}
