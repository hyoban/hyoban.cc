import type { APIRoute } from 'astro'
import { getSearchEntries } from '@/search-index'

export const GET: APIRoute = async () => Response.json(await getSearchEntries())
