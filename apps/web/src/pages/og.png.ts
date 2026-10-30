import type { APIRoute } from 'astro'
import { cardResponse, renderOgImage } from '@/og-image'

// The share card for pages without one of their own.
export const GET: APIRoute = async () => cardResponse(await renderOgImage())
