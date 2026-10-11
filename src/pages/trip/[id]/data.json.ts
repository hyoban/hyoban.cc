import type { APIRoute, GetStaticPaths } from 'astro'
import { getMoments, getTrips } from '@/moments'
import { getTripData } from '@/moments/trip-data'

export const getStaticPaths = (async () => {
  return getTrips(await getMoments()).map((trip) => ({ params: { id: trip.id }, props: { trip } }))
}) satisfies GetStaticPaths

type Props = Awaited<ReturnType<typeof getStaticPaths>>[number]['props']

export const GET: APIRoute<Props> = ({ props: { trip } }) => Response.json(getTripData(trip))
