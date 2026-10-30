import type { APIRoute } from 'astro'
import { getYearCollage } from '@/card-data'
import { getMoments } from '@/moments'
import { cachedCard, renderCollage } from '@/og-image'
import { getReviewYears } from '@/year-review'

// The picture under a year review's numerals: its months' photos, each cropped to its
// tile here, as one image the numerals cover rather than stretch.
const WIDTH = 1080
const HEIGHT = 400

export async function getStaticPaths() {
  const moments = await getMoments()
  return getReviewYears(moments).flatMap((year) => {
    const collage = getYearCollage(year, moments)
    return collage ? [{ params: { year: String(year) }, props: collage }] : []
  })
}

export const GET: APIRoute<{ photos: string[]; columns: number }> = async ({ props }) => {
  const image = await cachedCard('year-collage.webp', props, () =>
    renderCollage(props.photos, props.columns, WIDTH, HEIGHT),
  )
  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/webp' } })
}
