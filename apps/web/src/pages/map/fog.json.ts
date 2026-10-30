import type { APIRoute } from 'astro'
import { buildFogOfWar } from '@hyoban/trip-map'
import cities from '@/data/boundaries/cities.json'
import { getMappedLocationGroups, getMoments } from '@/moments'

// The cities the map's fog of war lifts over: those a Moment was written in, each weighed
// by how many were.
export const GET: APIRoute = async () =>
  Response.json(
    buildFogOfWar(
      cities as Parameters<typeof buildFogOfWar>[0],
      getMappedLocationGroups(await getMoments()).map((group) => ({
        ...group.location,
        weight: group.moments.length,
      })),
    ),
  )
