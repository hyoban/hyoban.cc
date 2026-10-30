import type { APIRoute } from 'astro'
import cities from '@/data/boundaries/cities.json'

// The regions the map's fog of war is drawn over (scripts/generate-city-boundaries.mjs).
export const GET: APIRoute = () => Response.json(cities)
