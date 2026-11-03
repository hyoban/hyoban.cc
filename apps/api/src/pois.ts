import type { PlaceKind } from '@hyoban/data-spec'

import { HttpError } from './http.ts'

// What is around a point in mainland China, from Tencent's place search, for the app to
// name a new place after: OpenStreetMap, which the app asks elsewhere, knows few of the
// shops there. The key stays here, in the TENCENT_MAP_KEY secret, so the app keeps none.
// Tencent works in GCJ-02, so the point goes there shifted and the places come back
// shifted to WGS 84, as the records and the map are.

const API = 'https://apis.map.qq.com/ws/place/v1'
/** How far around the point what is there is looked for, in metres. */
const AROUND_METRES = 400
const PAGE_SIZE = 20
/** How long an answer is kept, as Tencent allows each of its searches 200 calls a day. */
const KEPT_MS = 24 * 60 * 60 * 1000
const KEPT_MAX = 500

export interface Poi {
  name: string
  latitude: number
  longitude: number
  address?: string
  kind?: PlaceKind
  /** How far it is from the point asked about, to about 100 m, in metres. */
  distance?: number
}

export interface PoiQuery {
  latitude: number
  longitude: number
  /** Words to look for, nearest first; without them, what is around the point. */
  text?: string
}

/** `?lat=&lon=` and `?q=`, or what is wrong with them. */
export function readPoiQuery(params: URLSearchParams): PoiQuery | { error: string } {
  const latitude = Number(params.get('lat'))
  const longitude = Number(params.get('lon'))

  if (
    !params.get('lat') ||
    !params.get('lon') ||
    !(Math.abs(latitude) <= 90) ||
    !(Math.abs(longitude) <= 180)
  ) {
    return { error: 'lat and lon are a point in degrees.' }
  }

  const text = params.get('q')?.trim()

  // To about 100 m, so a pin moved a little asks what was asked already.
  return { latitude: snap(latitude), longitude: snap(longitude), ...(text && { text }) }
}

const kept = new Map<string, { at: number; pois: Poi[] }>()

/**
 * `findPois`, its answers kept a day in the Worker's memory: a best effort, as an isolate
 * comes and goes, and the Cache API isn't there on workers.dev.
 */
export async function keptPois(
  query: PoiQuery,
  key: string,
  fetcher: typeof fetch = fetch,
  now = Date.now(),
): Promise<Poi[]> {
  const id = `${query.latitude},${query.longitude},${query.text ?? ''}`
  const hit = kept.get(id)

  if (hit && now - hit.at < KEPT_MS) {
    return hit.pois
  }

  const pois = await findPois(query, key, fetcher)

  kept.delete(id)
  kept.set(id, { at: now, pois })

  if (kept.size > KEPT_MAX) {
    kept.delete(kept.keys().next().value!)
  }

  return pois
}

/**
 * The places [query] asks for; none outside mainland China, where Tencent knows little and
 * the app asks OpenStreetMap. A 503 when Tencent can't answer, its day's or second's
 * calls used up among them, so the app asks OpenStreetMap instead.
 */
export async function findPois(
  query: PoiQuery,
  key: string,
  fetcher: typeof fetch = fetch,
): Promise<Poi[]> {
  const here = { latitude: query.latitude, longitude: query.longitude }

  if (!inChina(here)) {
    return []
  }

  const shifted = wgsToGcj(here)
  const at = `${shifted.latitude},${shifted.longitude}`
  const url = query.text
    ? `${API}/suggestion?${new URLSearchParams({ keyword: query.text, location: at, page_size: `${PAGE_SIZE}`, key })}`
    : `${API}/explore?${new URLSearchParams({ boundary: `nearby(${at},${AROUND_METRES},0)`, policy: '2', page_size: `${PAGE_SIZE}`, key })}`
  const response = await fetcher(url)

  if (!response.ok) {
    throw new HttpError(503, `Tencent answered ${response.status}.`)
  }

  const answer = (await response.json()) as TencentAnswer

  if (answer.status !== 0) {
    throw new HttpError(503, `Tencent answered ${answer.status}: ${answer.message ?? ''}`.trim())
  }

  return (answer.data ?? []).flatMap((place) => {
    if (!place.title || !place.location) {
      return []
    }

    const point = gcjToWgs({ latitude: place.location.lat, longitude: place.location.lng })
    const kind = kindOf(place.category)

    return [
      {
        name: place.title,
        latitude: round(point.latitude),
        longitude: round(point.longitude),
        ...(place.address && { address: place.address }),
        ...(kind && { kind }),
        ...(place._distance !== undefined && { distance: place._distance }),
      },
    ]
  })
}

interface TencentAnswer {
  status: number
  message?: string
  data?: {
    title?: string
    address?: string
    category?: string
    location?: { lat: number; lng: number }
    _distance?: number
  }[]
}

/** What sort of place Tencent's category says it is, `美食:中餐厅:粤菜` food; none where it says too little. */
export function kindOf(category = ''): PlaceKind | undefined {
  const [top, second = ''] = category.split(':')

  switch (top) {
    case '美食':
      return 'food'
    case '购物':
      return 'shopping'
    case '酒店宾馆':
      return 'stay'
    case '旅游景点':
    case '自然地物':
    case '文化场馆':
      return 'scenic'
    case '基础设施':
      return second === '交通设施' ? 'transport' : undefined
    case '娱乐休闲':
      return /咖啡|茶|酒吧/.test(second) ? 'food' : undefined
    case '汽车':
      return /充电|加油|停车/.test(category) ? 'transport' : undefined
    default:
      return undefined
  }
}

interface Point {
  latitude: number
  longitude: number
}

/** Whether a point is where GCJ-02 shifts it: mainland China, roughly, as the app has it. */
function inChina({ latitude, longitude }: Point) {
  return longitude >= 72.004 && longitude <= 137.8347 && latitude >= 0.8293 && latitude <= 55.8271
}

/** A WGS 84 point as a map in China would give it. */
export function wgsToGcj(point: Point): Point {
  if (!inChina(point)) {
    return point
  }

  const [dLat, dLon] = shift(point.latitude, point.longitude)

  return { latitude: point.latitude + dLat, longitude: point.longitude + dLon }
}

/** A GCJ-02 point back in WGS 84, to well within a metre: `wgsToGcj` undone by iterating on it. */
export function gcjToWgs(point: Point): Point {
  if (!inChina(point)) {
    return point
  }

  let guess = point

  for (let step = 0; step < 10; step += 1) {
    const there = wgsToGcj(guess)
    const dLat = there.latitude - point.latitude
    const dLon = there.longitude - point.longitude

    guess = { latitude: guess.latitude - dLat, longitude: guess.longitude - dLon }

    if (Math.abs(dLat) < 1e-9 && Math.abs(dLon) < 1e-9) {
      break
    }
  }

  return guess
}

/** Krasovsky 1940, the ellipsoid GCJ-02 is worked on. */
const SEMI_MAJOR = 6378245
const ECCENTRICITY = 0.006693421622965943

/** How far GCJ-02 moves a point, in degrees of latitude and of longitude. */
function shift(latitude: number, longitude: number): [number, number] {
  const { PI, abs, cos, sin, sqrt } = Math
  const x = longitude - 105
  const y = latitude - 35
  let dLat =
    -100 +
    2 * x +
    3 * y +
    0.2 * y * y +
    0.1 * x * y +
    0.2 * sqrt(abs(x)) +
    ((20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2) / 3 +
    ((20 * sin(y * PI) + 40 * sin((y / 3) * PI)) * 2) / 3 +
    ((160 * sin((y / 12) * PI) + 320 * sin((y * PI) / 30)) * 2) / 3
  let dLon =
    300 +
    x +
    2 * y +
    0.1 * x * x +
    0.1 * x * y +
    0.1 * sqrt(abs(x)) +
    ((20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2) / 3 +
    ((20 * sin(x * PI) + 40 * sin((x / 3) * PI)) * 2) / 3 +
    ((150 * sin((x / 12) * PI) + 300 * sin((x / 30) * PI)) * 2) / 3
  const radians = (latitude / 180) * PI
  const magic = 1 - ECCENTRICITY * sin(radians) * sin(radians)
  const root = sqrt(magic)

  dLat = (dLat * 180) / (((SEMI_MAJOR * (1 - ECCENTRICITY)) / (magic * root)) * PI)
  dLon = (dLon * 180) / ((SEMI_MAJOR / root) * cos(radians) * PI)

  return [dLat, dLon]
}

function snap(degrees: number) {
  return Math.round(degrees * 1e3) / 1e3
}

/** To a few centimetres, as the records keep points. */
function round(degrees: number) {
  return Math.round(degrees * 1e6) / 1e6
}
