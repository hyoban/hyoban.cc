import { renderTripPreview as renderPreview } from '@hyoban/trip-map/preview'
import type { MomentTrip } from '@/moments'
import { getTripCovers } from '@/moments/trip-images'

/** A trip's share image: its route beside its first cover photo, in the site's light colours. */
export function renderTripPreview(trip: MomentTrip) {
  return renderPreview(trip, {
    // The site's --color-bg and --color-tx (see index.css).
    colors: { light: { background: '#fdfdfc', ink: '#21201c' } },
    cover: getTripCovers(trip)[0]?.src,
  })
}
