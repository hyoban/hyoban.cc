import { getAssetUrl, getFrameSources, getResizedSources } from './asset-url.ts'
import { getTextLang } from '@hyoban/trip-map'
import type { MomentRecord, TripRecord } from '@hyoban/data-spec'
import { readMomentRecords, readTripRecords } from './data/store.ts'

// A paragraph that is only a link to a trip, like `[国庆东北自驾](/trip/2026-national-day-road-trip/)`,
// becomes a grid of that trip's cover photos with the link below. The photos sit straight in the
// figure, not inside a link, so Telegram's automatic Instant View keeps them; the caption's link is
// stretched over the whole figure in CSS, so the photos still lead to the trip.

const TRIP_PATH = /^\/trip\/([\w-]+)\/?$/
// Three columns in the 40rem article column, inside the body's 1rem side padding.
const COVER_SIZES = '(min-width: 42rem) 13rem, calc((100vw - 2rem) / 3)'
const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '"': '&quot;',
  '<': '&lt;',
  '>': '&gt;',
}

type MdNode = { type: string; children?: MdNode[]; url?: string; value?: string }

export function remarkTripCovers() {
  let trips: Map<string, TripRecord> | undefined
  let media: Map<string, MomentRecord['media'][number]> | undefined

  return (tree: MdNode) => {
    trips ??= new Map(readTripRecords().map((trip) => [trip.id, trip]))
    media ??= new Map(
      readMomentRecords().flatMap(({ record }) =>
        record.media.map((item) => [`${record.uid}/${item.uid}`, item] as const),
      ),
    )
    replaceTripLinks(tree, trips, media)
  }
}

/**
 * A cover's photo, its sizes and its dimensions: a photo or live photo's still, a video's
 * poster, or for a video the API kept, its first frame.
 */
function getCoverStill(item: MomentRecord['media'][number] | undefined) {
  if (item?.type === 'video' && !item.published?.poster) {
    return item.published?.original && item.width && item.height
      ? {
          sources: getFrameSources(item.published.key, item.width),
          width: item.width,
          height: item.height,
        }
      : undefined
  }

  const still = item?.type === 'video' ? item.published?.poster : item?.published

  if (!still) {
    return undefined
  }

  return {
    sources: still.original
      ? getResizedSources(still)
      : [...still.variants, ...(still.width <= 1920 ? [still] : [])]
          .sort((first, second) => first.width - second.width)
          .map((source) => ({ src: getAssetUrl(source.key), width: source.width })),
    width: still.width,
    height: still.height,
  }
}

function replaceTripLinks(
  node: MdNode,
  trips: Map<string, TripRecord>,
  media: Map<string, MomentRecord['media'][number]>,
) {
  for (const [index, child] of (node.children ?? []).entries()) {
    const link = getStandaloneLink(child)
    const tripId = link?.url && TRIP_PATH.exec(link.url)?.[1]

    if (!link || !tripId) {
      replaceTripLinks(child, trips, media)
      continue
    }

    const trip = trips.get(tripId)

    if (!trip) {
      throw new Error(`A post links to unknown trip "${tripId}".`)
    }

    node.children![index] = {
      type: 'html',
      value: renderCovers(trip, link.url!, getText(link) || trip.title, media),
    }
  }
}

function getStandaloneLink(node: MdNode) {
  if (node.type !== 'paragraph') {
    return undefined
  }

  const children = (node.children ?? []).filter(
    (child) => child.type !== 'text' || child.value?.trim() !== '',
  )

  return children.length === 1 && children[0]!.type === 'link' ? children[0] : undefined
}

function renderCovers(
  trip: TripRecord,
  href: string,
  caption: string,
  media: Map<string, MomentRecord['media'][number]>,
) {
  const images = trip.covers.map((cover) => {
    const item = media.get(`${cover.moment}/${cover.media}`)
    const still = getCoverStill(item)

    if (!item || !still) {
      throw new Error(`Cover ${cover.moment}/${cover.media} of trip "${trip.id}" has no photo.`)
    }

    const { sources } = still
    const src = sources.find((source) => source.width >= 480) ?? sources.at(-1)!

    return `<img src="${escapeHtml(src.src)}" srcset="${escapeHtml(sources.map((source) => `${source.src} ${source.width}w`).join(', '))}" sizes="${COVER_SIZES}" width="${still.width}" height="${still.height}" alt="${escapeHtml(item.alt)}"${getTextLang(item.alt) ? ' lang="en"' : ''} loading="lazy" decoding="async">`
  })

  return `<figure class="trip-covers">${images.join('')}<figcaption><a href="${escapeHtml(href)}">${escapeHtml(caption)}</a></figcaption></figure>`
}

function getText(node: MdNode): string {
  return node.value ?? (node.children ?? []).map(getText).join('')
}

function escapeHtml(value: string) {
  return value.replace(/[&"<>]/g, (char) => HTML_ESCAPES[char]!)
}
