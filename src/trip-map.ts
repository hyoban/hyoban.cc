import { resolveMessages } from '@hyoban/trip-map'
import { SITE_TITLE, SITE_URL } from '@/consts'
import { getAssetUrl } from '@/asset-url'
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

export function getTripDescription(tripTitle: string, playing = false) {
  return playing
    ? `沿${tripTitle}的路线一站站回放，看每天的照片和见闻。`
    : `${tripTitle}的路线、照片和见闻。`
}
