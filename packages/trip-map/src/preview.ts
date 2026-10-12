import sharp, { type OverlayOptions } from 'sharp'
import type { TravelMode } from './trips.ts'

// A trip's preview, rendered with sharp at build time: its route on the left and a
// cover photo on the right. No text, so the build needs no CJK fonts. Serves as the
// share image and as a thumbnail, which also comes in a dark theme's colours.

const WIDTH = 1200
const HEIGHT = 630
// Near-white paper and near-black ink; a site passes its own theme's colours.
const COLORS = {
  dark: { background: '#111110', ink: '#eeeeec' },
  light: { background: '#fdfdfc', ink: '#21201c' },
}

export type PreviewTheme = keyof typeof COLORS
export type PreviewColors = Readonly<{ background: string; ink: string }>

/** The part of a built trip the preview draws: its places and the legs between them. */
export type PreviewTrip = Readonly<{
  legs: readonly Readonly<{
    by: TravelMode
    route?: Readonly<{ coordinates: readonly (readonly [number, number])[] }> | undefined
  }>[]
  path: readonly Readonly<{ id: string; latitude: number; longitude: number }>[]
}>

export type PreviewOptions = Readonly<{
  /** Each theme's colours; the ones not given use the defaults. */
  colors?: Partial<Record<PreviewTheme, PreviewColors>>
  /** The cover photo's URL, shown beside the route; without one the route gets the whole picture. */
  cover?: string | undefined
  /** Thickens the route for previews that will be shown small. */
  lineScale?: number
  theme?: PreviewTheme
}>

// The share image and the thumbnail are rendered separately; the cover is fetched once.
const coverPhotos = new Map<string, Promise<Buffer | undefined>>()

/** The preview as a PNG. */
export async function renderTripPreview(trip: PreviewTrip, options: PreviewOptions = {}) {
  const { cover, lineScale = 1, theme = 'light' } = options
  const colors = options.colors?.[theme] ?? COLORS[theme]
  const photo = await loadCoverPhoto(cover)
  const routeWidth = photo ? WIDTH / 2 : WIDTH
  const layers: OverlayOptions[] = [
    { input: Buffer.from(getRouteSvg(trip, routeWidth, lineScale, colors)), left: 0, top: 0 },
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
    create: { background: colors.background, channels: 3, height: HEIGHT, width: WIDTH },
  })
    .composite(layers)
    .png()
    .toBuffer()
}

function loadCoverPhoto(src: string | undefined) {
  if (!src) {
    return Promise.resolve(undefined)
  }

  let photo = coverPhotos.get(src)

  if (!photo) {
    photo = fetchCoverPhoto(src)
    coverPhotos.set(src, photo)
  }

  return photo
}

async function fetchCoverPhoto(src: string) {
  try {
    const response = await fetch(src)
    return response.ok ? Buffer.from(await response.arrayBuffer()) : undefined
  } catch {
    // Offline builds still get a route-only preview.
    return undefined
  }
}

/** The whole route along roads, scaled to fit, with a dot at each place. */
function getRouteSvg(
  trip: PreviewTrip,
  width: number,
  lineScale: number,
  { background, ink }: { background: string; ink: string },
) {
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
  // Each leg on its own, drives solid and ferries or flights dashed, as on the map.
  const paths = { car: '', straight: '' }
  // A ferry or flight taken both ways is drawn once, or the dashes fill each other in.
  const straightLegs = new Set<string>()

  for (const [index, leg] of trip.legs.entries()) {
    const from = trip.path[index]!
    const to = trip.path[index + 1]!

    if (leg.by !== 'car') {
      const ends = [from.id, to.id].sort().join(' ')

      if (straightLegs.has(ends)) {
        continue
      }

      straightLegs.add(ends)
    }
    const points = [
      [from.longitude, from.latitude] as const,
      ...(leg.route?.coordinates ?? []).map(([lng, lat]) => [lng, lat] as const),
      [to.longitude, to.latitude] as const,
    ]

    paths[leg.by === 'car' ? 'car' : 'straight'] += points
      .map((coordinates, pointIndex) => {
        const [x, y] = toPoint(coordinates)
        return `${pointIndex === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`
      })
      .join('')
  }

  const dots = trip.path.map((place) => {
    const [x, y] = toPoint([place.longitude, place.latitude])
    return `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${7 * lineScale}" fill="${background}" stroke="${ink}" stroke-width="${3 * lineScale}"/>`
  })

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${HEIGHT}" viewBox="0 0 ${width} ${HEIGHT}"><path d="${paths.car}" fill="none" stroke="${ink}" stroke-width="${5 * lineScale}" stroke-linecap="round" stroke-linejoin="round"/><path d="${paths.straight}" fill="none" stroke="${ink}" stroke-width="${5 * lineScale}" stroke-dasharray="${10 * lineScale} ${10 * lineScale}"/>${dots.join('')}</svg>`
}
