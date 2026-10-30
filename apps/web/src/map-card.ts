import {
  ALL_PLACES_MAX_ZOOM,
  createYearPalette,
  getPlacesExtent,
  getStaticMapUrl,
} from '@hyoban/trip-map'
import sharp from 'sharp'
import { canDrawMapPlaceholders, MAP_PLACEHOLDER_TOKEN } from '@/map-placeholder'
import { getMappedLocationGroups, getMapYears, getMoments, type Moment } from '@/moments'
import { OG_IMAGE_HEIGHT, OG_IMAGE_WIDTH } from '@/og-layout'
import { cachedOgAsset } from '@/og-cache'

// The map pages' share cards draw on a picture of the map: the period's places, every
// one for `all`, dotted in their years' colours as the map dots them, framed on the
// card's right side, clear of the words on its left.

// Where the places are framed on the card, in its pixels.
const FRAME = { bottom: 560, left: 560, right: 1130, top: 80 }
const TILE_SIZE = 512
const toX = (longitude: number) => (longitude + 180) / 360
const toY = (latitude: number) => {
  const sin = Math.sin((latitude * Math.PI) / 180)
  return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)
}

/**
 * A map card's background, twice the card's size, for a period's key as
 * getMapPlaceholderKey gives it; none when the build can't draw maps.
 */
export async function getMapCardBackground(key: string) {
  if (!(await canDrawMapPlaceholders())) return undefined

  const moments = await getMoments()
  const yearColor = createYearPalette(getMapYears(moments))
  const shown = getMapCardPlaces(key, moments)
  if (shown.length === 0) return undefined

  const { maxX, maxY, minX, minY } = getPlacesExtent(shown.map((group) => group.location))
  const roomX = FRAME.right - FRAME.left
  const roomY = FRAME.bottom - FRAME.top
  const zoom = Math.max(
    0,
    Math.min(
      ALL_PLACES_MAX_ZOOM,
      Math.log2(roomX / (TILE_SIZE * Math.max(maxX - minX, 1e-9))),
      Math.log2(roomY / (TILE_SIZE * Math.max(maxY - minY, 1e-9))),
    ),
  )
  const world = TILE_SIZE * 2 ** zoom
  // The card's middle, where the camera looks, from the frame's middle on the places' middle.
  const x = (minX + maxX) / 2 - ((FRAME.left + FRAME.right) / 2 - OG_IMAGE_WIDTH / 2) / world
  const y = (minY + maxY) / 2 - ((FRAME.top + FRAME.bottom) / 2 - OG_IMAGE_HEIGHT / 2) / world
  const camera = {
    latitude: (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI,
    longitude: x * 360 - 180,
    zoom,
  }
  const dots = shown.map((group) => ({
    color: yearColor(group.firstYear),
    left: (toX(group.location.longitude) - x) * world + OG_IMAGE_WIDTH / 2,
    top: (toY(group.location.latitude) - y) * world + OG_IMAGE_HEIGHT / 2,
  }))
  const url = getStaticMapUrl(camera, {
    height: OG_IMAGE_HEIGHT,
    retina: true,
    style: 'mapbox/light-v11',
    token: MAP_PLACEHOLDER_TOKEN,
    width: OG_IMAGE_WIDTH,
  })

  const circles = dots
    .map(
      (dot) =>
        `<circle cx="${(dot.left * 2).toFixed(1)}" cy="${(dot.top * 2).toFixed(1)}" r="18" fill="${dot.color}" stroke="#fff" stroke-width="6"/>`,
    )
    .join('')
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${OG_IMAGE_WIDTH * 2}" height="${OG_IMAGE_HEIGHT * 2}">${circles}</svg>`

  return cachedOgAsset('map-card.png', [url.replace(/access_token=[^&]*/, ''), svg], async () => {
    const response = await fetch(url)
    if (!response.ok) {
      throw new Error(`The map card ${key} failed: ${response.status} ${await response.text()}`)
    }
    return sharp(Buffer.from(await response.arrayBuffer()))
      .composite([{ input: Buffer.from(svg) }])
      .png()
      .toBuffer()
  })
}

/** Actual places in the period; an empty period must not claim every place was visited. */
export function getMapCardPlaces(key: string, moments: Moment[]) {
  return getMappedLocationGroups(moments)
    .filter((group) =>
      group.moments.some((moment) => key === 'all' || moment.dateKey.startsWith(key)),
    )
    .toSorted((first, second) => first.firstYear - second.firstYear)
}
