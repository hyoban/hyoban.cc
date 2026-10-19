import type { MomentMedia, MomentRecord, Place, Places } from './index.ts'

// The rules for publishing: which place a new one is, what a Moment's published id is,
// and what its media files are called. Shared by the API and `pnpm data:publish`.

/** Two places this close, or with the same name, are one place. */
export const NEARBY_KM = 0.2

const ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

/**
 * Where a place goes among the known ones: a known id stays as it is, a place within
 * 200 m of a known one or with its name takes that one's id, and anything else is new.
 */
export function matchPlace(
  id: string,
  place: Place,
  places: Places,
): { id: string; status: 'known' | 'matched' | 'new' } {
  if (places[id]) {
    return { id, status: 'known' }
  }

  const match = Object.entries(places).find(
    ([, candidate]) =>
      candidate.name === place.name || getDistanceKm(candidate, place) <= NEARBY_KM,
  )

  return match ? { id: match[0], status: 'matched' } : { id, status: 'new' }
}

const EARTH_RADIUS_KM = 6371

/** The great-circle distance between two places, in kilometres. */
export function getDistanceKm(from: Place, to: Place) {
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180
  const latitudeDelta = toRadians(to.latitude - from.latitude)
  const longitudeDelta = toRadians(to.longitude - from.longitude)
  const a =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(toRadians(from.latitude)) *
      Math.cos(toRadians(to.latitude)) *
      Math.sin(longitudeDelta / 2) ** 2

  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a))
}

/** The slug the app proposed, else the first place, else the first words, else `moment`. */
export function getSlug(record: Pick<MomentRecord, 'places' | 'text'> & { slug?: unknown }) {
  const proposed = typeof record.slug === 'string' ? record.slug : ''

  if (ID_PATTERN.test(proposed)) {
    return proposed
  }

  if (record.places[0]) {
    return record.places[0]
  }

  const words = record.text
    .normalize('NFKD')
    .toLowerCase()
    .replaceAll(/[^a-z0-9]+/g, ' ')
    .trim()
    .split(' ')
    .filter(Boolean)
    .slice(0, 5)

  return words.length > 0 ? words.join('-') : 'moment'
}

/**
 * A Moment's published id: the next order on its day among `orders`, and its slug,
 * numbered when another Moment that day already took the id.
 */
export function getPublishedId(
  dateKey: string,
  slug: string,
  { orders, ids }: { orders: number[]; ids: Set<string> },
) {
  const order = Math.max(0, ...orders) + 1
  const prefix = `${dateKey.slice(0, 4)}/${dateKey.slice(5, 7)}/${dateKey.slice(8, 10)}-${String(order).padStart(2, '0')}-`
  let id = `${prefix}${slug}`

  for (let attempt = 2; ids.has(id); attempt += 1) {
    id = `${prefix}${slug}-${attempt}`
  }

  return { id, order, slug: id.slice(prefix.length) }
}

/** The file stem of a media item: the slug, numbered among the record's images or videos. */
export function getStem(
  record: { media: MomentMedia[]; published: { slug: string } },
  item: MomentMedia,
) {
  const isVideo = item.type === 'video'
  const siblings = record.media.filter((candidate) => (candidate.type === 'video') === isVideo)
  const taken = new Set(
    record.media.flatMap((candidate) =>
      candidate.published ? [getKeyStem(candidate.published.key)] : [],
    ),
  )
  const base = `${record.published.slug}${isVideo ? '-video' : ''}`

  for (let index = siblings.indexOf(item) + 1; ; index += 1) {
    const stem = index === 1 ? base : `${base}-${index}`

    if (!taken.has(stem)) {
      return stem
    }
  }
}

/** The file extension for a media type the API takes, or undefined for any other. */
export function getExtension(contentType: string) {
  return EXTENSIONS[contentType.split(';')[0]!.trim().toLowerCase()]
}

const EXTENSIONS: Record<string, string> = {
  'image/avif': 'avif',
  'image/gif': 'gif',
  'image/heic': 'heic',
  'image/heif': 'heif',
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/webm': 'webm',
}

function getKeyStem(key: string) {
  return key.slice(key.lastIndexOf('/') + 1).replace(/\.[^.]+$/, '')
}
