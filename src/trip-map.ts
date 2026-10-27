import { resolveMessages } from '@hyoban/trip-map'
import { SITE_TITLE, SITE_URL } from '@/consts'
import { getAssetUrl } from '@/asset-url'
import { CAR_MODEL } from '@/car-model'
import type { MomentTrip } from '@/moments'
import { tripVideoMusic } from '@/data/music'

// How the site sets up @hyoban/trip-map: its words, its music, and who it is. Shared
// by the map page's server render and its client script, so it stays DOM-free.

export const tripMapMessages = resolveMessages({
  // The only videos Moments link to are on Bilibili.
  videoTitle: '哔哩哔哩视频',
})

export const tripMapBrand = { image: getAssetUrl('site/avatar-80.webp'), name: SITE_TITLE }

/** The 007GT of the year review (src/car-model.ts), in its lighter cut, small enough on the
 * map, driving trips flown over. */
export const tripMapCarModel = CAR_MODEL

export const tripMapMusic = tripVideoMusic.map(({ src, title }) => ({ src, title }))

/** Where a video's viewers can find the trips, on its summary card. */
export const tripMapSiteLine = `${new URL(SITE_URL).host}/trip/`

// The site's document titles are a page's name, then the site's: `<name> | Hyoban`. A name
// says its section, `2026 年 6 月\u2003地图`, only when another section's page has the same
// one: a period names a calendar month, a map and a trip list alike. The map's client
// script sets them too as its address changes, so they are built here, for both.

type Period = { month?: number | undefined; year: number }

const formatPeriod = ({ month, year }: Period) =>
  month === undefined ? `${year} 年` : `${year} 年 ${month} 月`

export function getDocumentTitle(name: string) {
  return `${name} | ${SITE_TITLE}`
}

export function getMapHeading(period?: Period) {
  return period ? `${formatPeriod(period)}\u2003地图` : '地图'
}

export function getTripListHeading(period?: Period) {
  return period ? `${formatPeriod(period)}\u2003旅程` : '旅程'
}

/** A trip's page title, or its playback's, which shares the trip's name. */
export function getTripTitle(tripTitle: string, playing = false) {
  return getDocumentTitle(playing ? `${tripTitle}\u2003回放` : tripTitle)
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
