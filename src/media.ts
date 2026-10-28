// The films and series recommended on /media/, as src/data/media-recommendations.json lists
// them and scripts/generate-media.mjs fetches their TMDB details: each known by its type and
// TMDB id.

type MediaItem = { tmdbId: number; type: 'movie' | 'tv' }

/** `movie:550`: a recommendation's key, matching it to its generated details. */
export function getMediaItemKey(media: MediaItem) {
  return `${media.type}:${media.tmdbId}`
}

/** By TMDB id, then films before series. */
export function compareMediaItems(first: MediaItem, second: MediaItem) {
  return first.tmdbId - second.tmdbId || first.type.localeCompare(second.type)
}
