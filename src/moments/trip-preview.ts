import sharp, { type OverlayOptions } from 'sharp'
import type { getTrips } from '@/moments'
import { getTripCovers } from '@/moments/trip-images'

// A trip's preview: its route on the left and its first cover photo on the right. No
// text, so the build needs no CJK fonts. It is the share image and the trip list's
// thumbnail.

const WIDTH = 1200
const HEIGHT = 630
const BACKGROUND = '#fdfdfc'
const INK = '#21201c'

type BuiltTrip = ReturnType<typeof getTrips>[number]

/** `lineScale` thickens the route for previews that will be shown small. */
export async function renderTripPreview(trip: BuiltTrip, lineScale = 1) {
  const photo = await loadCoverPhoto(getTripCovers(trip)[0]?.src)
  const routeWidth = photo ? WIDTH / 2 : WIDTH
  const layers: OverlayOptions[] = [
    { input: Buffer.from(getRouteSvg(trip, routeWidth, lineScale)), left: 0, top: 0 },
  ]

  if (photo) {
    layers.push({
      input: await sharp(photo)
        .resize(WIDTH - routeWidth, HEIGHT, { fit: 'cover' })
        .toBuffer(),
      left: routeWidth,
      top: 0,
    })
  }

  return sharp({
    create: { background: BACKGROUND, channels: 3, height: HEIGHT, width: WIDTH },
  })
    .composite(layers)
    .png()
    .toBuffer()
}

async function loadCoverPhoto(src: string | undefined) {
  if (!src) {
    return undefined
  }

  try {
    const response = await fetch(src)
    return response.ok ? Buffer.from(await response.arrayBuffer()) : undefined
  } catch {
    // Offline builds still get a route-only preview.
    return undefined
  }
}

/** The whole route along roads, scaled to fit, with a dot at each place. */
function getRouteSvg(trip: BuiltTrip, width: number, lineScale: number) {
  const line: [number, number][] = []

  for (const [index, place] of trip.path.entries()) {
    if (index > 0) {
      line.push(
        ...(trip.legs[index - 1]?.route?.coordinates ?? []).map(
          ([lng, lat]) => [lng, lat] as [number, number],
        ),
      )
    }

    line.push([place.longitude, place.latitude])
  }

  const meanLatitude = line.reduce((total, [, lat]) => total + lat, 0) / Math.max(line.length, 1)
  const xScale = Math.cos((meanLatitude * Math.PI) / 180)
  const projected = line.map(([lng, lat]) => [lng * xScale, -lat] as const)
  const xs = projected.map(([x]) => x)
  const ys = projected.map(([, y]) => y)
  const [minX, maxX, minY, maxY] = [
    Math.min(...xs),
    Math.max(...xs),
    Math.min(...ys),
    Math.max(...ys),
  ]
  const padding = 72
  const scale = Math.min(
    (width - padding * 2) / Math.max(maxX - minX, 1e-6),
    (HEIGHT - padding * 2) / Math.max(maxY - minY, 1e-6),
  )
  const offsetX = (width - (maxX - minX) * scale) / 2
  const offsetY = (HEIGHT - (maxY - minY) * scale) / 2
  const toPoint = ([lng, lat]: readonly [number, number]) =>
    [(lng * xScale - minX) * scale + offsetX, (-lat - minY) * scale + offsetY] as const
  const path = line.map((coordinates, index) => {
    const [x, y] = toPoint(coordinates)
    return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`
  })
  const dots = trip.path.map((place) => {
    const [x, y] = toPoint([place.longitude, place.latitude])
    return `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${7 * lineScale}" fill="${BACKGROUND}" stroke="${INK}" stroke-width="${3 * lineScale}"/>`
  })

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${HEIGHT}" viewBox="0 0 ${width} ${HEIGHT}"><path d="${path.join('')}" fill="none" stroke="${INK}" stroke-width="${5 * lineScale}" stroke-linecap="round" stroke-linejoin="round"/>${dots.join('')}</svg>`
}
