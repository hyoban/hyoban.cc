import generated from '@/data/media.generated.json'
import recommendations from '@/data/media-recommendations.json'

/** Six stable picks, alternating animation, series and movies from the recommendation list. */
export function getMediaPosters() {
  const byKey = new Map(generated.items.map((item) => [`${item.type}:${item.tmdbId}`, item]))
  const groups = new Map<string, string[]>()
  for (const recommendation of recommendations) {
    const item = byKey.get(`${recommendation.type}:${recommendation.tmdbId}`)
    if (!item?.data.poster_path) continue
    const category = item.data.genres?.some((genre) => genre.id === 16) ? 'anime' : item.type
    const photos = groups.get(category) ?? []
    photos.push(`https://image.tmdb.org/t/p/w500${item.data.poster_path}`)
    groups.set(category, photos)
  }
  const photos: string[] = []
  const seen = new Set<string>()
  for (let index = 0; photos.length < 6; index++) {
    let remaining = false
    for (const group of groups.values()) {
      const photo = group[index]
      if (!photo) continue
      remaining = true
      if (seen.has(photo)) continue
      seen.add(photo)
      photos.push(photo)
      if (photos.length === 6) break
    }
    if (!remaining) break
  }
  return { photos, count: recommendations.length }
}
