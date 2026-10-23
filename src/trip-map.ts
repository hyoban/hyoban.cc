import { resolveMessages } from '@hyoban/trip-map'
import { SITE_TITLE, SITE_URL } from '@/consts'
import { getAssetUrl } from '@/asset-url'
import type { MomentTrip } from '@/moments'
import { tripVideoMusic } from '@/data/music'

// How the site sets up @hyoban/trip-map: its words, its music, and who it is. Shared
// by the map page's server render and its client script, so it stays DOM-free.

export const tripMapMessages = resolveMessages({
  // The only videos Moments link to are on Bilibili.
  videoTitle: '哔哩哔哩视频',
})

export const tripMapBrand = { image: getAssetUrl('site/avatar-80.webp'), name: SITE_TITLE }

export const tripMapMusic = tripVideoMusic.map(({ src, title }) => ({ src, title }))

/** Where a video's viewers can find the trips, on its summary card. */
export const tripMapSiteLine = `${new URL(SITE_URL).host}/trip/`

/** A trip's page title, or its playback's, which shares the trip's card but not its words. */
export function getTripTitle(tripTitle: string, playing = false) {
  return `${tripTitle} · ${playing ? '旅程回放' : '旅程'} | ${SITE_TITLE}`
}

/**
 * What a trip's page says beside its title, and not again what the title or its card does:
 * how far it was driven, and how many records and photos it has.
 */
export function getTripDescription(trip: MomentTrip, playing = false) {
  const photos = trip.stops.reduce((total, stop) => total + stop.media.length, 0)
  const totals = [
    // A trip mostly flown or by train drives a few kilometres to and from stations: not
    // worth naming as if it were a road trip.
    trip.drivingKm >= 100 && `自驾 ${trip.drivingKm.toLocaleString('zh-CN')} 公里`,
    `${trip.stops.length} 条记录`,
    photos > 0 && `${photos} 张照片`,
  ]
    .filter(Boolean)
    .join('、')
  return playing ? `沿路线一站站回放，${totals}。` : `${totals}。`
}
