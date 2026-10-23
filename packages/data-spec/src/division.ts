import type { Place } from './index.ts'

// Which province, city and district a place's point falls in, from Aliyun DataV's
// GeoAtlas: China's divisions as GeoJSON, `<adcode>_full.json` holding the children of
// one. `pnpm places:locate` fills data/ with it, and the API each place it is sent.

export const DATAV_SOURCE = 'https://geo.datav.aliyun.com/areas_v3/bound'

/** Beijing, Tianjin, Shanghai, Chongqing, Taiwan, Hong Kong and Macau: their own city. */
const WHOLE = new Set(['11', '12', '31', '50', '71', '81', '82'])
/** How far off a division's edge a point still takes it, in kilometres: a pier, a bridge. */
const COAST_KM = 3

export type Division = NonNullable<Place['division']>

type Ring = readonly (readonly number[])[]
export type Geometry =
  | Readonly<{ type: 'Polygon'; coordinates: readonly Ring[] }>
  | Readonly<{ type: 'MultiPolygon'; coordinates: readonly (readonly Ring[])[] }>

/** One division under another: its adcode, its name and its outline in GCJ-02. */
export type DivisionArea = Readonly<{ code: string; name: string; geometry: Geometry }>

/** The divisions directly under `code`, `100000` being China; none for one without. */
export type ChildrenOf = (code: string) => Promise<readonly DivisionArea[]>

/**
 * The division a place is in: down to the district, a city stopping above it and a region
 * at its province. Undefined for a point no division holds, out at sea.
 */
export async function locateDivision(
  place: Pick<Place, 'latitude' | 'longitude' | 'kind'>,
  childrenOf: ChildrenOf,
): Promise<Division | undefined> {
  // The places are WGS-84 and DataV's outlines GCJ-02, some hundred metres apart.
  const point = toGcj02(place.longitude, place.latitude)
  const province = find(await childrenOf('100000'), point)

  if (!province) {
    return undefined
  }

  const division: Division = { code: province.code, province: province.name }

  if (place.kind === 'region') {
    return division
  }

  // A municipality's own children are its districts.
  const city = WHOLE.has(province.code.slice(0, 2))
    ? province
    : find(await childrenOf(province.code), point)

  if (!city) {
    return division
  }

  Object.assign(division, { code: city.code, city: city.name })

  if (place.kind === 'city') {
    return division
  }

  const district = find(await childrenOf(city.code), point)

  return district ? { ...division, code: district.code, district: district.name } : division
}

/**
 * Reads the children from DataV, each division once; a division without children, like
 * Dongguan, answers 404 and has none.
 */
export function createDataVChildren(
  source = DATAV_SOURCE,
  fetchJson: (url: string) => Promise<unknown> = defaultFetchJson,
): ChildrenOf {
  const cache = new Map<string, Promise<readonly DivisionArea[]>>()

  return (code) => {
    let children = cache.get(code)

    if (!children) {
      children = fetchJson(`${source}/${code}_full.json`).then(toAreas)
      cache.set(code, children)
    }

    return children
  }
}

// The spec builds without the DOM's or a Worker's types; both have fetch.
declare const fetch: (url: string) => Promise<{
  ok: boolean
  status: number
  statusText: string
  json(): Promise<unknown>
}>

async function defaultFetchJson(url: string) {
  const response = await fetch(url)

  if (response.status === 404) {
    return { features: [] }
  }

  if (!response.ok) {
    throw new Error(`${url}: ${response.status} ${response.statusText}`)
  }

  return response.json()
}

function toAreas(collection: unknown): DivisionArea[] {
  const features = (collection as { features?: unknown[] } | null)?.features ?? []

  return features.flatMap((feature) => {
    const { geometry, properties } = feature as {
      geometry?: Geometry | null
      properties?: { adcode?: string | number; name?: unknown }
    }
    const code = String(properties?.adcode ?? '')

    // DataV marks the line round the South China Sea's islands `100000_JD`.
    return geometry && /^\d{6}$/.test(code) && typeof properties?.name === 'string'
      ? [{ code, name: properties.name, geometry }]
      : []
  })
}

/** The area holding `point`, or the nearest within COAST_KM. */
function find(areas: readonly DivisionArea[], point: readonly [number, number]) {
  let nearest: { area: DivisionArea; distance: number } | undefined

  for (const area of areas) {
    if (contains(area.geometry, point)) {
      return area
    }

    const distance = distanceTo(area.geometry, point)

    if (distance <= COAST_KM && (!nearest || distance < nearest.distance)) {
      nearest = { area, distance }
    }
  }

  return nearest?.area
}

function polygonsOf(geometry: Geometry): readonly (readonly Ring[])[] {
  return geometry.type === 'Polygon' ? [geometry.coordinates] : geometry.coordinates
}

function contains(geometry: Geometry, [x, y]: readonly [number, number]) {
  return polygonsOf(geometry).some((rings) => {
    let inside = false

    for (const ring of rings) {
      for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const [xi, yi] = ring[i] as [number, number]
        const [xj, yj] = ring[j] as [number, number]

        if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) {
          inside = !inside
        }
      }
    }

    return inside
  })
}

/** Kilometres from `point` to the nearest edge of `geometry`, on a local flat plane. */
function distanceTo(geometry: Geometry, [x, y]: readonly [number, number]) {
  const kx = 111.32 * Math.cos((y * Math.PI) / 180)
  const ky = 110.57
  let best = Infinity

  for (const rings of polygonsOf(geometry)) {
    for (const ring of rings) {
      for (let i = 1; i < ring.length; i += 1) {
        const [x0, y0] = ring[i - 1] as [number, number]
        const [x1, y1] = ring[i] as [number, number]
        const ax = (x0 - x) * kx
        const ay = (y0 - y) * ky
        const dx = (x1 - x0) * kx
        const dy = (y1 - y0) * ky
        const t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (dx * dx + dy * dy || 1)))
        best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy))
      }
    }
  }

  return best
}

/** The standard WGS-84 to GCJ-02 shift, applied everywhere: only China's outlines are read. */
export function toGcj02(longitude: number, latitude: number): [number, number] {
  const a = 6378245
  const ee = 0.006693421622965943
  const x = longitude - 105
  const y = latitude - 35
  let dLat =
    -100 +
    2 * x +
    3 * y +
    0.2 * y * y +
    0.1 * x * y +
    0.2 * Math.sqrt(Math.abs(x)) +
    ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3 +
    ((20 * Math.sin(y * Math.PI) + 40 * Math.sin((y / 3) * Math.PI)) * 2) / 3 +
    ((160 * Math.sin((y / 12) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30)) * 2) / 3
  let dLng =
    300 +
    x +
    2 * y +
    0.1 * x * x +
    0.1 * x * y +
    0.1 * Math.sqrt(Math.abs(x)) +
    ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3 +
    ((20 * Math.sin(x * Math.PI) + 40 * Math.sin((x / 3) * Math.PI)) * 2) / 3 +
    ((150 * Math.sin((x / 12) * Math.PI) + 300 * Math.sin((x / 30) * Math.PI)) * 2) / 3
  const radians = (latitude / 180) * Math.PI
  const magic = 1 - ee * Math.sin(radians) ** 2

  dLat = (dLat * 180) / (((a * (1 - ee)) / (magic * Math.sqrt(magic))) * Math.PI)
  dLng = (dLng * 180) / ((a / Math.sqrt(magic)) * Math.cos(radians) * Math.PI)

  return [longitude + dLng, latitude + dLat]
}
