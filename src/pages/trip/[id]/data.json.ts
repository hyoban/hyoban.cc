import type { APIRoute } from 'astro'
import { getTripPaths, type TripPathProps } from '@/moments'
import { getTripData } from '@/moments/trip-data'

export const getStaticPaths = getTripPaths

export const GET: APIRoute<TripPathProps> = ({ props: { trip } }) =>
  Response.json(getTripData(trip))
