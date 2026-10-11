import type { APIRoute, GetStaticPaths } from 'astro'
import sharp from 'sharp'
import { getMoments, getTrips } from '@/moments'
import { renderTripPreview } from '@/moments/trip-preview'

export const getStaticPaths = (async () => {
  return getTrips(await getMoments()).map((trip) => ({ params: { id: trip.id }, props: { trip } }))
}) satisfies GetStaticPaths

type Props = Awaited<ReturnType<typeof getStaticPaths>>[number]['props']

export const GET: APIRoute<Props> = async ({ props: { trip } }) => {
  const image = await sharp(await renderTripPreview(trip, 2.5))
    .resize(480)
    .webp({ quality: 80 })
    .toBuffer()

  return new Response(new Uint8Array(image), { headers: { 'Content-Type': 'image/webp' } })
}
