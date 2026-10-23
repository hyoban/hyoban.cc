import type { APIRoute } from 'astro'
import sharp from 'sharp'
import { getTripPaths, type TripPathProps } from '@/moments'
import { renderTripCard } from '@/trip-card'

export const getStaticPaths = getTripPaths

export const GET: APIRoute<TripPathProps> = async ({ props: { trip } }) => {
  const image = await sharp(await renderTripCard(trip))
    .jpeg({ mozjpeg: true, quality: 82 })
    .toBuffer()

  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/jpeg' } })
}
