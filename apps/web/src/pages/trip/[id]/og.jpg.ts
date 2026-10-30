import type { APIRoute } from 'astro'
import { getTripPaths, type TripPathProps } from '@/moments'
import { cardResponse } from '@/og-image'
import { renderTripCard } from '@/trip-card'

export const getStaticPaths = getTripPaths

export const GET: APIRoute<TripPathProps> = async ({ props: { trip } }) => {
  return cardResponse(await renderTripCard(trip), 'jpg')
}
