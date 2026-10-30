import { getAllPlacesCamera, projectOnCamera, TRIP_FRAME_MAX_ZOOM } from '@hyoban/trip-map'

// A trip page's route drawn over the picture standing in for the map while it loads (see
// CalendarMap.astro), framed as the map opens on it, flat (getOpeningView in
// @hyoban/trip-map): so the map, once loaded, draws its own route right where this one
// was, and only then leans back. Drawn as the page is swapped in, before it's seen (see
// trip-transition.ts), or as it loads.

type Segment = { line: [number, number][]; straight: boolean }

// The trip's sidebar as the map measures it beside its route: CalendarMap.astro's
// `.trip-map-side`, on the right, or at a phone's foot, and the margin the map keeps.
const SIDE_WIDTH = 448
const SIDE_HEIGHT = 576
// What the sidebar may leave of the map at least, to frame the route beside it at all.
const MIN_FRAMED = 240
// How far up from the map's foot the legend reaches beside the sidebar.
const LEGEND = 78

/** Draws the route in `svg` for a map `width` by `height` pixels, as the map frames it. */
export function drawRouteGlyph(svg: SVGSVGElement, width: number, height: number) {
  // A phone's, as the map has it: the sidebar at its foot.
  const narrow = matchMedia('(max-width: 48rem)').matches
  const segments = JSON.parse(svg.dataset.tripMapRouteGlyph ?? '[]') as Segment[]
  const places = segments.flatMap((segment) =>
    segment.line.map(([longitude, latitude]) => ({ latitude, longitude })),
  )
  if (places.length === 0) return

  let inset: { bottom?: number; legend?: number; right?: number } = narrow
    ? { bottom: Math.min(height * 0.6, SIDE_HEIGHT) + 12 }
    : { right: Math.min(SIDE_WIDTH, width - 32) + 16, legend: LEGEND }
  if (Math.min(width - (inset.right ?? 0), height - (inset.bottom ?? 0)) < MIN_FRAMED) inset = {}

  const size = { height, width }
  const camera = getAllPlacesCamera(places, {
    ...size,
    inset,
    maxZoom: TRIP_FRAME_MAX_ZOOM,
    narrow,
  })
  const document = svg.ownerDocument
  const paths = segments.map((segment) => {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path')
    const points = segment.line.map(([longitude, latitude]) => {
      const { x, y } = projectOnCamera(camera, size, { latitude, longitude })
      return `${x.toFixed(1)} ${y.toFixed(1)}`
    })
    path.setAttribute('d', `M${points.join('L')}`)
    if (segment.straight) path.setAttribute('data-straight', '')
    return path
  })

  svg.setAttribute('viewBox', `0 0 ${width} ${height}`)
  svg.replaceChildren(...paths)
  svg.setAttribute('data-trip-map-route-glyph-placed', '')
}

/** Draws the route of a page that loaded with one, rather than being swapped in. */
document.addEventListener('astro:page-load', () => {
  const svg = document.querySelector<SVGSVGElement>(
    '[data-trip-map-route-glyph]:not([data-trip-map-route-glyph-placed])',
  )
  const page = svg?.closest('[data-trip-map]')
  if (svg && page) {
    drawRouteGlyph(svg, page.clientWidth, page.clientHeight)
  }
})
