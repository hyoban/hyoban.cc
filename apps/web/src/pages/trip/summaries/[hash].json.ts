import type { APIRoute } from 'astro'
import { getTripSummaries } from '@/moments/trip-data'

// Every trip's route and title, which the map fetches for its overview; the name changes
// with the content (see getTripSummaries).
export async function getStaticPaths() {
  const { path } = await getTripSummaries()

  return [{ params: { hash: path.slice(path.lastIndexOf('/') + 1, -'.json'.length) } }]
}

export const GET: APIRoute = async () =>
  new Response((await getTripSummaries()).json, { headers: { 'Content-Type': 'application/json' } })
