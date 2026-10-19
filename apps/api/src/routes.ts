import {
  momentRecordSchema,
  placesSchema,
  routeSchema,
  tripRecordSchema,
  type Place,
  type Places,
  type Route,
  type TripRecord,
} from '@hyoban/data-spec'
import {
  buildTrip,
  compareMoments,
  getLegKey,
  getTrainLegKey,
  getTripSummary,
  simplifyLine,
  type TripDefinition,
  type TripLocation,
  type TripRoute,
} from '@hyoban/trip-map'
import { readPlaces, readRoutes, readTrips, type Database } from './database.ts'
import { REVISION, routeRow } from './rows.ts'
import { commit, upsert, type WriteContext } from './writes.ts'

// The trips' routes. Each leg between two consecutive places of a trip is driven along
// the road Mapbox's Directions finds, or for a train leg along the rail line
// OpenRailRouting's public demo finds between the places' stations; a ferry or a flight
// is drawn straight. After a write the site shows, the API works out every leg the
// published trips have, fetches the ones it doesn't have yet, and marks deleted the ones
// no trip has any more, so the site's build and the app read the same routes.

/** A place's point to route from: its station for a train, else the place. */
type Point = Readonly<{ latitude: number; longitude: number; name: string }>

/** A leg to fetch: a drive between two places, or a rail line between their stations. */
export type Leg = Readonly<{ by: 'car' | 'train'; from: Point; to: Point }>

/** Fetches one leg's route; undefined when there is none, as for a drive across the sea. */
export type RouteFetcher = (leg: Leg) => Promise<Route | undefined>

/** About 30 m; keeps city streets readable without storing every road vertex. */
const SIMPLIFY_TOLERANCE = 0.0003

/**
 * How many legs one write fetches at most, so a Worker stays well inside its limit on
 * requests; the rest are fetched after the next write.
 */
export const FETCHES_PER_RUN = 30

/** The published trips built from the database, each with its legs' routes where it has them. */
export async function readBuiltTrips(
  db: Database,
  routes: Readonly<Record<string, TripRoute>> = {},
) {
  const places = placesSchema.parse(await readPlaces(db))
  const trips = (await readTrips(db)).map((trip) => tripRecordSchema.parse(trip))
  const { results } = await db
    .prepare(
      `SELECT record FROM moments
       WHERE deleted_at IS NULL AND published_id IS NOT NULL AND hidden = 0`,
    )
    .all<{ record: string }>()
  const resolveLocation = (id: string): TripLocation & Partial<Pick<Place, 'station'>> => {
    const place = places[id]

    if (!place) {
      throw new Error(`Unknown place id "${id}".`)
    }

    return { id, ...place }
  }
  // As the site takes them: visible Moments with their known places, oldest first.
  const moments = results
    .map((row) => momentRecordSchema.parse(JSON.parse(row.record)))
    .map((moment) => ({
      dateKey: moment.occurredAt.slice(0, 10),
      locations: moment.places.filter((id) => id in places).map(resolveLocation),
      occurredAt: moment.occurredAt,
      order: moment.published!.order,
    }))
    .sort(compareMoments)

  return trips
    .map((trip) => buildTrip(toDefinition(trip), moments, { resolveLocation, routes }))
    .filter((trip) => trip.stops.length > 0)
}

/** Every leg the trips need fetched, by its key: drives not driven back the same way, and trains. */
export function getNeededLegs(trips: Awaited<ReturnType<typeof readBuiltTrips>>, places: Places) {
  const legs = new Map<string, Leg>()

  for (const trip of trips) {
    for (const leg of trip.legs) {
      if (leg.by === 'train') {
        const station = (id: string, point: Point): Point => places[id]?.station ?? point

        legs.set(getTrainLegKey(leg.from, leg.to), {
          by: 'train',
          from: station(leg.from.id, leg.from),
          to: station(leg.to.id, leg.to),
        })
        continue
      }

      const key = getLegKey(leg.from, leg.to)
      const [from, to] = key.split(';')

      // A drive back the same way reuses the route there, reversed.
      if (leg.by === 'car' && !leg.retraced && from !== to) {
        legs.set(key, { by: 'car', from: leg.from, to: leg.to })
      }
    }
  }

  return legs
}

/**
 * Brings the routes up to date with the trips: fetches the legs missing, up to
 * `FETCHES_PER_RUN`, and marks deleted the ones no trip needs. One transaction, and none
 * when nothing changed. Returns what it did.
 */
export async function fillRoutes(context: WriteContext, fetchRoute: RouteFetcher) {
  const { db } = context
  const places = placesSchema.parse(await readPlaces(db))
  const existing = await readRoutes(db)
  const needed = getNeededLegs(await readBuiltTrips(db), places)
  const fetched = new Map<string, Route>()
  const missing = [...needed].filter(([key]) => !(key in existing))
  let failed = 0

  for (const [key, leg] of missing.slice(0, FETCHES_PER_RUN)) {
    try {
      const route = await fetchRoute(leg)

      if (route) {
        fetched.set(key, routeSchema.parse(route))
      } else {
        console.warn(
          `No ${leg.by === 'train' ? 'rail line' : 'road'} for ${leg.from.name} → ${leg.to.name}; drawn straight`,
        )
      }
    } catch (caught) {
      failed += 1
      console.error(`Fetching ${leg.from.name} → ${leg.to.name} failed`, caught)
    }
  }

  const unused = Object.keys(existing).filter((key) => !needed.has(key))

  if (fetched.size > 0 || unused.length > 0) {
    await commit(context, [
      ...[...fetched].map(([key, route]) => upsert(db, 'routes', routeRow(key, route))),
      ...unused.map((key) =>
        db
          .prepare(`UPDATE routes SET deleted_at = ?1, rev = ${REVISION} WHERE key = ?2`)
          .bind(context.now, key),
      ),
    ])
  }

  return {
    fetched: fetched.size,
    removed: unused.length,
    failed,
    left: Math.max(0, missing.length - FETCHES_PER_RUN),
  }
}

/**
 * Each published trip's route as the site's overview draws it: drives one after another
 * as one stretch, and each train, plane or ferry as a stretch of its own, drawn dashed.
 */
export async function readTripRoutes(db: Database) {
  const routes = (await readRoutes(db)) as Record<string, TripRoute>
  const trips = await readBuiltTrips(db, routes)

  return Object.fromEntries(trips.map((trip) => [trip.id, getTripSummary(trip).segments]))
}

/** Fetches legs from Mapbox's Directions with `token`, and train legs from OpenRailRouting. */
export function createRouteFetcher(token: string, fetcher: typeof fetch = fetch): RouteFetcher {
  return async (leg) =>
    leg.by === 'train' ? fetchRail(leg, fetcher) : fetchDrive(leg, token, fetcher)
}

async function fetchDrive({ from, to }: Leg, token: string, fetcher: typeof fetch) {
  const url = new URL(`https://api.mapbox.com/directions/v5/mapbox/driving/${getLegKey(from, to)}`)
  url.searchParams.set('geometries', 'geojson')
  url.searchParams.set('overview', 'full')
  url.searchParams.set('access_token', token)

  const response = await fetcher(url)
  const body = (await response.json()) as {
    code?: string
    message?: string
    routes?: { geometry: { coordinates: [number, number][] }; distance: number; duration: number }[]
  }

  if (body.code === 'NoRoute') {
    return undefined
  }

  const [route] = body.routes ?? []

  if (!response.ok || body.code !== 'Ok' || !route) {
    throw new Error(`Directions answered ${response.status}: ${body.message ?? body.code}`)
  }

  return {
    coordinates: simplify(route.geometry.coordinates),
    distance: Math.round(route.distance),
    duration: Math.round(route.duration),
  }
}

async function fetchRail({ from, to }: Leg, fetcher: typeof fetch) {
  const url = new URL('https://routing.openrailrouting.org/route')
  url.searchParams.append('point', `${from.latitude},${from.longitude}`)
  url.searchParams.append('point', `${to.latitude},${to.longitude}`)
  url.searchParams.set('profile', 'tgv_all')
  url.searchParams.set('points_encoded', 'false')
  url.searchParams.set('instructions', 'false')

  // The demo turns away requests without a User-Agent of their own.
  const response = await fetcher(url, { headers: { 'User-Agent': 'hyoban.cc trip routes' } })
  const body = (await response.json()) as {
    message?: string
    paths?: { points: { coordinates: [number, number][] }; distance: number; time: number }[]
  }
  const [path] = body.paths ?? []

  if (!response.ok || !path) {
    if (body.message?.includes('not found')) {
      return undefined
    }

    throw new Error(`OpenRailRouting answered ${response.status}: ${body.message}`)
  }

  return {
    coordinates: simplify(path.points.coordinates),
    distance: Math.round(path.distance),
    duration: Math.round(path.time / 1000),
  }
}

function simplify(line: [number, number][]) {
  return simplifyLine(line, SIMPLIFY_TOLERANCE).map(
    ([longitude, latitude]) =>
      [Number(longitude.toFixed(5)), Number(latitude.toFixed(5))] as [number, number],
  )
}

/** The trip-map model's view of a record: legs as tuples, and no covers (they're uids). */
function toDefinition(record: TripRecord): TripDefinition {
  return {
    end: record.end,
    id: record.id,
    sameWayBack: record.sameWayBack.map(({ from, to }) => [from, to] as const),
    start: record.start,
    straightLegs: record.straightLegs.map(({ from, to, by }) => [from, to, by] as const),
    title: record.title,
    waypoints: record.waypoints.map(({ at, place }) => ({
      ...(at === undefined ? {} : { at }),
      location: place,
    })),
  }
}
