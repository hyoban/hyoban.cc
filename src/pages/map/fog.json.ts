import type { APIRoute } from 'astro'
import { buildFogOfWar } from '@hyoban/trip-map'
import cities from '@/data/boundaries/cities.json'
import { getMappedLocationGroups, getMoments } from '@/moments'

// The cities the map's fog of war leaves lit: those a Moment was written in.
export const GET: APIRoute = async () =>
  Response.json(
    buildFogOfWar(
      cities as Parameters<typeof buildFogOfWar>[0],
      getMappedLocationGroups(await getMoments()).map((group) => group.location),
    ),
  )
