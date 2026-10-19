import type { APIRoute } from 'astro'
import { pngResponse, renderOgImage } from '@/og-image'

// The share card for pages without one of their own.
export const GET: APIRoute = async () => pngResponse(await renderOgImage())
