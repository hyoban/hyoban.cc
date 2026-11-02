import { pinyin } from 'pinyin-pro'

import type { Place, Places } from './index.ts'

// The rules for publishing: which place a new one is, and what type of file a media item
// is stored as; its key is `getMediaKey`'s, by uids alone. The API's.

/** The id the app gives a place it made, `p-<ulid>`, until publishing names it by its pinyin. */
export const PROVISIONAL_PLACE_PATTERN = /^p-[0-9a-hjkmnp-tv-z]{26}$/

/** A place id spelled from its name: pinyin for Chinese, the letters and digits of the rest. */
export function getPlaceId(name: string, taken: Record<string, unknown> = {}) {
  const base =
    pinyin(name, { toneType: 'none', type: 'array', nonZh: 'consecutive' })
      .join('-')
      .toLowerCase()
      .normalize('NFKD')
      .replaceAll(/[^a-z0-9]+/g, '-')
      .replaceAll(/^-+|-+$/g, '') || 'place'

  let id = base

  for (let attempt = 2; id in taken; attempt += 1) {
    id = `${base}-${attempt}`
  }

  return id
}

/**
 * Where a place goes among the known ones: a known id stays as it is, one brought in from
 * another app's venue takes the id of that venue's place if there is one, and anything else
 * is new. Places near each other or of the same name stay apart: two shops in one mall are
 * two places, and choosing a known one is the app's to do as the place is picked.
 */
export function matchPlace(
  id: string,
  place: Place,
  places: Places,
): { id: string; status: 'known' | 'matched' | 'new' } {
  if (places[id]) {
    return { id, status: 'known' }
  }

  const source = place.source
  const same =
    source &&
    Object.entries(places).find(
      ([, candidate]) =>
        candidate.source?.app === source.app && candidate.source.venue === source.venue,
    )

  return same ? { id: same[0], status: 'matched' } : { id, status: 'new' }
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
