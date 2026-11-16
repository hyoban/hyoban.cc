import { createYearPalette, getStaticMapUrl, TRIP_FRAME_MAX_ZOOM } from '@hyoban/trip-map'
import { renderTripPreview } from '@hyoban/trip-map/preview'
import sharp from 'sharp'
import { getMapYears, getMoments, type MomentTrip } from '@/moments'
import { getTripCovers } from '@/moments/trip-images'
import { getCardPhotos } from '@/card-data'
import { canDrawMapPlaceholders, MAP_PLACEHOLDER_TOKEN } from '@/map-placeholder'
import { cachedCard, encodeCard, renderOgImage } from '@/og-image'
import { cachedOgAsset } from '@/og-cache'
import { OG_COLORS, OG_IMAGE_HEIGHT, OG_IMAGE_WIDTH } from '@/og-layout'

// A trip's share card, drawn as the site's map draws the trip: a light map framed on the
// trip on the card's right side, the route in its year's colour over it, flights and
// ferries dashed, its stops dotted, and a few of its stops' photos pinned at their places,
// framed in white; its name, dates and what it came to on the left, where the map fades
// into the card. A build that can't draw maps keeps the route drawn alone beside its cover.

// Where the trip is framed on the card, in its pixels: clear of the words on the left.
const FRAME = { bottom: 570, left: 560, right: 1140, top: 60 }
const TILE_SIZE = 512
// The map is drawn at twice the card's size, as the card's other maps are.
const SCALE = 2
// A pinned photo's side, its white frame and how far apart two pins' middles keep.
const PIN = 116
const PIN_FRAME = 6
const PIN_GAP = 150
// How far above its stop's dot a pin's foot stands.
const PIN_LIFT = 14
const PINS = 3

const toX = (longitude: number) => (longitude + 180) / 360
const toY = (latitude: number) => {
  const sin = Math.sin((latitude * Math.PI) / 180)
  return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)
}

/** The card for a trip, as a JPEG. */
export async function renderTripCard(trip: MomentTrip) {
  // How long it was, how far it was driven, as the page's description counts it (a trip
  // mostly flown drives too little to name), and how many records it has.
  const detail = [
    `${trip.dayCount} 天`,
    trip.drivingKm >= 100 && `${trip.drivingKm.toLocaleString('zh-CN')} 公里`,
    `${trip.stops.length} 条记录`,
  ]
    .filter(Boolean)
    .join(' ')
  const date = `${trip.start.replaceAll('-', '.')} — ${trip.end.replaceAll('-', '.')}`
  const background = await getTripCardMap(trip)

  if (!background) {
    return renderRouteCard(trip, date, detail)
  }

  return cachedCard('trip-map.jpg', [trip.title, date, detail, background], async () =>
    encodeCard(await renderOgImage({ title: trip.title, date, detail, background }), 'jpg'),
  )
}

/** The card as it was before maps: the route drawn alone beside the cover. */
async function renderRouteCard(trip: MomentTrip, date: string, detail: string) {
  const photo = getTripCovers(trip)[0]?.src ?? getCardPhotos(trip.stops, 1)[0]
  return cachedCard('trip.jpg', [trip, photo, detail], async () => {
    const route = await renderTripPreview(trip, {
      colors: { light: { background: OG_COLORS.bg, ink: OG_COLORS.tx } },
      lineScale: 1.5,
    })
    const card = await renderOgImage({
      title: trip.title,
      date,
      detail,
      panel: {
        kind: 'route',
        route: `data:image/png;base64,${Buffer.from(route).toString('base64')}`,
        photo,
      },
    })
    return encodeCard(card, 'jpg')
  })
}

/** The trip's legs as lines of [longitude, latitude], each a drive along its road or straight. */
function getLegLines(trip: MomentTrip) {
  return trip.legs.map((leg, index) => {
    const from = trip.path[index]!
    const to = trip.path[index + 1]!
    const road = leg.by !== 'plane' && leg.by !== 'ferry' ? leg.route?.coordinates : undefined
    return {
      dashed: !road && leg.by !== 'car' && leg.by !== 'train',
      line: [
        [from.longitude, from.latitude],
        ...(road ?? []),
        [to.longitude, to.latitude],
      ] as (readonly [number, number])[],
    }
  })
}

/**
 * The card's map, twice the card's size: the static map framed on the trip with its route,
 * stops and pinned photos drawn over it; none when the build can't draw maps.
 */
async function getTripCardMap(trip: MomentTrip) {
  if (!(await canDrawMapPlaceholders()) || trip.path.length === 0) return undefined

  const legs = getLegLines(trip)
  const all = [
    ...legs.flatMap((leg) => leg.line),
    ...trip.path.map((place) => [place.longitude, place.latitude] as const),
  ]
  const xs = all.map(([longitude]) => toX(longitude))
  const ys = all.map(([, latitude]) => toY(latitude))
  const [minX, maxX, minY, maxY] = [
    Math.min(...xs),
    Math.max(...xs),
    Math.min(...ys),
    Math.max(...ys),
  ]
  // Room for the pins round the route: half a pin on either side, and a whole one above,
  // where a pin stands over its stop.
  const pinHeight = PIN + PIN_FRAME * 2 + PIN_LIFT
  const frame = {
    bottom: FRAME.bottom,
    left: FRAME.left + pinHeight / 2,
    right: FRAME.right - pinHeight / 2,
    top: FRAME.top + pinHeight,
  }
  const roomX = frame.right - frame.left
  const roomY = frame.bottom - frame.top
  const zoom = Math.max(
    0,
    Math.min(
      TRIP_FRAME_MAX_ZOOM,
      Math.log2(roomX / (TILE_SIZE * Math.max(maxX - minX, 1e-9))),
      Math.log2(roomY / (TILE_SIZE * Math.max(maxY - minY, 1e-9))),
    ),
  )
  const world = TILE_SIZE * 2 ** zoom
  // The card's middle, where the camera looks, from the frame's middle on the trip's middle.
  const x = (minX + maxX) / 2 - ((frame.left + frame.right) / 2 - OG_IMAGE_WIDTH / 2) / world
  const y = (minY + maxY) / 2 - ((frame.top + frame.bottom) / 2 - OG_IMAGE_HEIGHT / 2) / world
  const project = ([longitude, latitude]: readonly [number, number]) =>
    [
      ((toX(longitude) - x) * world + OG_IMAGE_WIDTH / 2) * SCALE,
      ((toY(latitude) - y) * world + OG_IMAGE_HEIGHT / 2) * SCALE,
    ] as const
  const camera = {
    latitude: (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI,
    longitude: x * 360 - 180,
    zoom,
  }
  const url = getStaticMapUrl(camera, {
    height: OG_IMAGE_HEIGHT,
    retina: true,
    style: 'mapbox/light-v11',
    token: MAP_PLACEHOLDER_TOKEN,
    width: OG_IMAGE_WIDTH,
  })

  const color = createYearPalette(getMapYears(await getMoments()))(Number(trip.start.slice(0, 4)))
  const path = (line: readonly (readonly [number, number])[]) =>
    line
      .map(
        (point, index) =>
          `${index ? 'L' : 'M'}${project(point)
            .map((value) => value.toFixed(1))
            .join(' ')}`,
      )
      .join('')
  const casing = legs.map(
    (leg) =>
      `<path d="${path(leg.line)}" fill="none" stroke="#fff" stroke-width="18" stroke-linecap="round" stroke-linejoin="round"/>`,
  )
  const routes = legs.map(
    (leg) =>
      `<path d="${path(leg.line)}" fill="none" stroke="${color}" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"${leg.dashed ? ' stroke-dasharray="2 22"' : ''}/>`,
  )
  const stopPoints = trip.stops.map((stop) =>
    project([stop.locations[0]!.longitude, stop.locations[0]!.latitude]),
  )
  const dots = stopPoints.map(
    ([left, top]) =>
      `<circle cx="${left.toFixed(1)}" cy="${top.toFixed(1)}" r="11" fill="#fff" stroke="${color}" stroke-width="7"/>`,
  )
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${OG_IMAGE_WIDTH * SCALE}" height="${OG_IMAGE_HEIGHT * SCALE}">${casing.join('')}${routes.join('')}${dots.join('')}</svg>`
  const pins = getPins(trip, stopPoints)

  return cachedOgAsset(
    'trip-card-map.png',
    [url.replace(/access_token=[^&]*/, ''), svg, pins],
    async () => {
      const response = await fetch(url)
      if (!response.ok) {
        throw new Error(
          `The trip card ${trip.id}'s map failed: ${response.status} ${await response.text()}`,
        )
      }
      const framed = await Promise.all(pins.map((pin) => framePhoto(pin.photo)))
      return sharp(Buffer.from(await response.arrayBuffer()))
        .composite([
          { input: Buffer.from(svg) },
          ...framed.flatMap((input, index) => {
            if (!input) return []
            const side = (PIN + PIN_FRAME * 2) * SCALE
            const [left, top] = pins[index]!.at
            return [
              {
                input,
                left: Math.round(left - side / 2),
                top: Math.round(top - side - PIN_LIFT * SCALE),
              },
            ]
          }),
        ])
        .png()
        .toBuffer()
    },
  )
}

/**
 * Up to [PINS] of the trip's photos to pin at their stops, each a stop's first photo, chosen
 * from the first stop on so that they keep apart and inside the card; above its stop's dot.
 */
function getPins(trip: MomentTrip, points: readonly (readonly [number, number])[]) {
  const pins: { at: readonly [number, number]; photo: string }[] = []
  const candidates = trip.stops.flatMap((stop, index) => {
    const photo = getCardPhotos([stop], 1, 480)[0]
    return photo ? [{ at: points[index]!, photo }] : []
  })
  // The first, the last and the middle, then the rest, so the pins spread along the route.
  const order =
    candidates.length > 2
      ? [0, candidates.length - 1, Math.floor(candidates.length / 2), ...candidates.keys()]
      : [...candidates.keys()]
  const half = ((PIN + PIN_FRAME * 2) / 2) * SCALE

  for (const index of new Set(order)) {
    const candidate = candidates[index]!
    const [left, top] = candidate.at
    const inside =
      left - half >= FRAME.left * SCALE - 40 &&
      left + half <= OG_IMAGE_WIDTH * SCALE - 16 &&
      top - half * 2 - PIN_LIFT * SCALE >= 16
    const apart = pins.every(({ at }) => Math.hypot(at[0] - left, at[1] - top) >= PIN_GAP * SCALE)

    if (inside && apart) pins.push(candidate)
    if (pins.length >= PINS) break
  }

  return pins
}

/** A photo as a pin's picture: square, its corners rounded, in a white frame with a soft shadow. */
async function framePhoto(src: string) {
  const response = await fetch(src)
  if (!response.ok) return undefined
  const side = PIN * SCALE
  const frame = PIN_FRAME * SCALE
  const outer = side + frame * 2
  const radius = 14 * SCALE
  const photo = await sharp(Buffer.from(await response.arrayBuffer()))
    .rotate()
    .resize(side, side, { fit: 'cover' })
    .composite([
      {
        input: Buffer.from(
          `<svg width="${side}" height="${side}"><rect width="${side}" height="${side}" rx="${radius - frame}" fill="#fff"/></svg>`,
        ),
        blend: 'dest-in',
      },
    ])
    .png()
    .toBuffer()
  return sharp({
    create: { width: outer, height: outer, channels: 4, background: '#0000' },
  })
    .composite([
      {
        input: Buffer.from(
          `<svg width="${outer}" height="${outer}"><rect width="${outer}" height="${outer}" rx="${radius}" fill="#fff"/></svg>`,
        ),
      },
      { input: photo, left: frame, top: frame },
    ])
    .png()
    .toBuffer()
}
