import type { APIRoute } from 'astro'
import { getLifeCollage } from '@/card-data'
import { getMoments } from '@/moments'
import { cachedCard, renderCollage } from '@/og-image'
import { getReviewYears } from '@/year-review'

// The picture under the whole life's title, as the years' under theirs (../[year]/collage.webp.ts).
const WIDTH = 1080
const HEIGHT = 400

export const GET: APIRoute = async () => {
  const moments = await getMoments()
  const collage = getLifeCollage(getReviewYears(moments), moments)
  if (!collage) return new Response(null, { status: 404 })
  const image = await cachedCard('life-collage.webp', collage, () =>
    renderCollage(collage.photos, collage.columns, WIDTH, HEIGHT),
  )
  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/webp' } })
}
