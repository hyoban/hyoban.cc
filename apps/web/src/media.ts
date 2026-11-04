// The films and series recommended on /media/, as src/data/media-recommendations.json lists
// them and scripts/generate-media.mjs fetches their TMDB details: each known by its type and
// TMDB id.

/** The page's tabs: /media/ shows animation, each other its own page, /media/<category>/. */
export const mediaCategories = [
  { label: '动漫', value: 'anime' },
  { label: '电视剧', value: 'tv' },
  { label: 'TVB', value: 'tvb' },
  { label: '电影', value: 'movie' },
  { label: '音乐', value: 'music' },
] as const

export type MediaCategory = (typeof mediaCategories)[number]['value']

export const DEFAULT_MEDIA_CATEGORY: MediaCategory = 'anime'

/** /media/ or /media/<category>/. */
export function getMediaCategoryPath(category: MediaCategory) {
  return category === DEFAULT_MEDIA_CATEGORY ? '/media/' : `/media/${category}/`
}

type MediaItem = { tmdbId: number; type: 'movie' | 'tv' }

/** `movie:550`: a recommendation's key, matching it to its generated details. */
export function getMediaItemKey(media: MediaItem) {
  return `${media.type}:${media.tmdbId}`
}

/** By TMDB id, then films before series. */
export function compareMediaItems(first: MediaItem, second: MediaItem) {
  return first.tmdbId - second.tmdbId || first.type.localeCompare(second.type)
}
