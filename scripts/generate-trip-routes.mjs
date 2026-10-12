import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { trips } from '../src/data/trips.ts'
import { buildTrip, getLegKey, getTrainLegKey } from '../src/moments/trips.ts'
import { readTripMoments, resolveLocation } from './lib/trip-moments.mjs'

// Fetches a driving route for every trip leg that isn't cached yet, so trip playback
// can follow roads, and a rail line for every train leg from OpenRailRouting's public
// demo (high-speed lines, no key). Run it after adding a trip or moving a place; cached
// legs are reused and legs no trip uses any more are dropped.

const root = fileURLToPath(new URL('../', import.meta.url))
const routesFile = join(root, 'src/data/trip-routes.json')
// About 30 m; keeps city streets readable without storing every road vertex.
const SIMPLIFY_TOLERANCE = 0.0003
// Where a train leg leaves and arrives, by location id: rail lines start at stations,
// and a city's centre may be nearer some other track. Places not listed use their own point.
const STATIONS = {
  beijing: { latitude: 39.8652, longitude: 116.3786, name: '北京南站' },
  changsha: { latitude: 28.1475, longitude: 113.0646, name: '长沙南站' },
  chengdu: { latitude: 30.6296, longitude: 104.1427, name: '成都东站' },
  guangzhou: { latitude: 22.989, longitude: 113.269, name: '广州南站' },
  'hong-kong': { latitude: 22.3046, longitude: 114.1653, name: '香港西九龙站' },
  nanjing: { latitude: 31.9686, longitude: 118.7976, name: '南京南站' },
  qingdao: { latitude: 36.1714, longitude: 120.3732, name: '青岛北站' },
  'qingdao-tsutaya-haitian': { latitude: 36.1714, longitude: 120.3732, name: '青岛北站' },
  shenzhen: { latitude: 22.6097, longitude: 114.0292, name: '深圳北站' },
}

const token = process.env.VITE_MAPBOX_TOKEN || (await readEnvToken())

if (!token) {
  throw new Error('Set VITE_MAPBOX_TOKEN in .env or the environment.')
}

const moments = await readTripMoments(root)
const cached = JSON.parse(await readFile(routesFile, 'utf8'))
const routes = {}
let fetched = 0

for (const trip of trips) {
  for (const leg of buildTrip(trip, moments, resolveLocation).legs) {
    if (leg.by === 'train') {
      const key = getTrainLegKey(leg.from, leg.to)

      if (routes[key]) {
        continue
      }

      if (cached[key]) {
        routes[key] = cached[key]
        continue
      }

      const from = STATIONS[leg.from.id] ?? leg.from
      const to = STATIONS[leg.to.id] ?? leg.to
      const route = await fetchRailRoute(from, to)
      fetched += 1

      if (route) {
        routes[key] = route
        console.log(
          `${trip.id}: ${from.name} → ${to.name} ${(route.distance / 1000).toFixed(1)} km by rail`,
        )
      } else {
        console.warn(`${trip.id}: no rail line for ${from.name} → ${to.name}; drawn straight`)
      }

      continue
    }

    const key = getLegKey(leg.from, leg.to)
    const [from, to] = key.split(';')

    // A drive back the same way reuses the route there, reversed.
    if (leg.by !== 'car' || leg.retraced || from === to || routes[key]) {
      continue
    }

    // Routes cached before durations were stored are fetched again once.
    if (cached[key]?.duration !== undefined) {
      routes[key] = cached[key]
      continue
    }

    const route = await fetchRoute(key)
    fetched += 1

    if (route) {
      routes[key] = route
      console.log(
        `${trip.id}: ${leg.from.name} → ${leg.to.name} ${(route.distance / 1000).toFixed(1)} km`,
      )
    } else {
      console.warn(
        `${trip.id}: no driving route for ${leg.from.name} → ${leg.to.name}; drawn straight`,
      )
    }
  }
}

await writeFile(routesFile, formatRoutes(routes))
const dropped = Object.keys(cached).filter((key) => !routes[key]).length

console.log(`${Object.keys(routes).length} routes (${fetched} fetched, ${dropped} dropped).`)

async function fetchRoute(key) {
  const url = new URL(`https://api.mapbox.com/directions/v5/mapbox/driving/${key}`)
  url.searchParams.set('geometries', 'geojson')
  url.searchParams.set('overview', 'full')
  url.searchParams.set('access_token', token)

  const response = await fetch(url)
  const body = await response.json()

  if (body.code === 'NoRoute') {
    return undefined
  }

  if (!response.ok || body.code !== 'Ok') {
    throw new Error(`Directions request failed for ${key}: ${body.message ?? body.code}`)
  }

  const [route] = body.routes

  return {
    coordinates: simplify(route.geometry.coordinates, SIMPLIFY_TOLERANCE).map(([lng, lat]) => [
      Number(lng.toFixed(5)),
      Number(lat.toFixed(5)),
    ]),
    distance: Math.round(route.distance),
    duration: Math.round(route.duration),
  }
}

async function fetchRailRoute(from, to) {
  const url = new URL('https://routing.openrailrouting.org/route')
  url.searchParams.append('point', `${from.latitude},${from.longitude}`)
  url.searchParams.append('point', `${to.latitude},${to.longitude}`)
  url.searchParams.set('profile', 'tgv_all')
  url.searchParams.set('points_encoded', 'false')
  url.searchParams.set('instructions', 'false')

  // The demo turns away requests without a User-Agent of their own.
  const response = await fetch(url, { headers: { 'User-Agent': 'hyoban.cc trip routes' } })
  const body = await response.json()

  if (!response.ok || !body.paths) {
    if (body.message?.includes('not found')) {
      return undefined
    }

    throw new Error(`Rail route request failed for ${from.name} → ${to.name}: ${body.message}`)
  }

  const [path] = body.paths

  return {
    coordinates: simplify(path.points.coordinates, SIMPLIFY_TOLERANCE).map(([lng, lat]) => [
      Number(lng.toFixed(5)),
      Number(lat.toFixed(5)),
    ]),
    distance: Math.round(path.distance),
    duration: Math.round(path.time / 1000),
  }
}

/** Ramer–Douglas–Peucker in degrees, which is close enough at this tolerance. */
function simplify(points, tolerance) {
  if (points.length <= 2) {
    return points
  }

  const keep = new Uint8Array(points.length)
  const stack = [[0, points.length - 1]]
  keep[0] = 1
  keep[points.length - 1] = 1

  while (stack.length > 0) {
    const [start, end] = stack.pop()
    let farthest = -1
    let farthestDistance = tolerance

    for (let index = start + 1; index < end; index += 1) {
      const distance = getSegmentDistance(points[index], points[start], points[end])

      if (distance > farthestDistance) {
        farthest = index
        farthestDistance = distance
      }
    }

    if (farthest !== -1) {
      keep[farthest] = 1
      stack.push([start, farthest], [farthest, end])
    }
  }

  return points.filter((_, index) => keep[index])
}

function getSegmentDistance([x, y], [x1, y1], [x2, y2]) {
  const dx = x2 - x1
  const dy = y2 - y1
  const lengthSquared = dx * dx + dy * dy
  const t =
    lengthSquared === 0
      ? 0
      : Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / lengthSquared))

  return Math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))
}

/** One route per line keeps the file diffable without spreading every vertex out. */
function formatRoutes(routes) {
  const entries = Object.keys(routes)
    .sort()
    .map((key) => `  ${JSON.stringify(key)}: ${JSON.stringify(routes[key])}`)

  return entries.length > 0 ? `{\n${entries.join(',\n')}\n}\n` : '{}\n'
}

async function readEnvToken() {
  try {
    const env = await readFile(join(root, '.env'), 'utf8')
    const line = env.split('\n').find((item) => item.startsWith('VITE_MAPBOX_TOKEN='))
    return line
      ?.slice('VITE_MAPBOX_TOKEN='.length)
      .trim()
      .replace(/^["']|["']$/g, '')
  } catch {
    return undefined
  }
}
