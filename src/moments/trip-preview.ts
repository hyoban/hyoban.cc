import { renderTripPreview as renderPreview, type PreviewTheme } from '@hyoban/trip-map/preview'
import type { MomentTrip } from '@/moments'
import { getTripCovers } from '@/moments/trip-images'

// The site's --color-bg and --color-tx in each theme (see index.css).
const COLORS = {
  dark: { background: '#111110', ink: '#eeeeec' },
  light: { background: '#fdfdfc', ink: '#21201c' },
}

export type { PreviewTheme }

/**
 * A trip's share image and thumbnail: its route beside its first cover photo.
 * `lineScale` thickens the route for previews that will be shown small.
 */
export function renderTripPreview(trip: MomentTrip, lineScale = 1, theme: PreviewTheme = 'light') {
  return renderPreview(trip, {
    colors: COLORS,
    cover: getTripCovers(trip)[0]?.src,
    lineScale,
    theme,
  })
}
