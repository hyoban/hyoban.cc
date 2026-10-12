import type { APIRoute } from 'astro'
import sharp from 'sharp'
import { getTripPaths, type TripPathProps } from '@/moments'
import { renderTripPreview } from '@/moments/trip-preview'

export const getStaticPaths = getTripPaths

// The trip list's thumbnail for the dark theme, so its route half isn't a white block.
export const GET: APIRoute<TripPathProps> = async ({ props: { trip } }) => {
  const image = await sharp(await renderTripPreview(trip, 2.5, 'dark'))
    .resize(480)
    .webp({ quality: 80 })
    .toBuffer()

  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/webp' } })
}
