import { renderTripPreview } from '@hyoban/trip-map/preview'
import type { MomentTrip } from '@/moments'
import { getTripCovers } from '@/moments/trip-images'
import { getCardPhotos } from '@/card-data'
import { cachedCard, encodeCard, renderOgImage } from '@/og-image'
import { OG_COLORS } from '@/og-layout'

/** Keep the route and cover, with the trip's identity readable beside them, as a JPEG. */
export async function renderTripCard(trip: MomentTrip) {
  const photo = getTripCovers(trip)[0]?.src ?? getCardPhotos(trip.stops, 1)[0]
  return cachedCard('trip.jpg', [trip, photo], async () => {
    const route = await renderTripPreview(trip, {
      colors: { light: { background: OG_COLORS.bg, ink: OG_COLORS.tx } },
      lineScale: 1.5,
    })
    const card = await renderOgImage({
      title: trip.title,
      date: `${trip.start.replaceAll('-', '.')} — ${trip.end.replaceAll('-', '.')}`,
      detail: `${trip.dayCount} 天\u2003${trip.placeCount} 个地点`,
      panel: {
        kind: 'route',
        route: `data:image/png;base64,${Buffer.from(route).toString('base64')}`,
        photo,
      },
    })
    return encodeCard(card, 'jpg')
  })
}
