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
import pLimit from 'p-limit'
import {
  placesQuery,
  readAll,
  revisionQuery,
  routesQuery,
  toRecordsByKey,
  toRevision,
  TRIP_ROUTES,
  TRIP_ROUTES_ETAG,
  TRIP_ROUTES_REVISION,
  tripsQuery,
  type Database,
} from './database.ts'
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

/** How many legs are fetched at once, so a run stays well inside `waitUntil`'s time. */
const FETCHES_AT_ONCE = 6

/**
 * Where `meta` keeps the keys of the legs the routers found no way along, as a drive
 * across the sea, so they are drawn straight and not asked for again; a moved place is
 * another leg, and asked for.
 */
const NO_ROUTE = 'routes_none'

/** What the trips are built from, read at one revision. */
export async function readTripInputs(db: Database) {
  const [revision, places, trips, moments, routes, none] = await readAll(db, [
    revisionQuery(db),
    placesQuery(db),
    tripsQuery(db),
    db.prepare(
      `SELECT record FROM moments
       WHERE deleted_at IS NULL AND published = 1 AND hidden = 0 AND kind = 'moment'`,
    ),
    routesQuery(db),
    db.prepare(`SELECT value FROM meta WHERE key = ?1`).bind(NO_ROUTE),
  ])
  const [noRoute] = none as { value: string }[]

  return {
    revision: toRevision(revision),
    places: placesSchema.parse(toRecordsByKey(places)),
    trips: (trips as { record: string }[]).map((row) =>
      tripRecordSchema.parse(JSON.parse(row.record)),
    ),
    moments: (moments as { record: string }[]).map((row) =>
      momentRecordSchema.parse(JSON.parse(row.record)),
    ),
    routes: toRecordsByKey(routes) as Record<string, TripRoute>,
    noRoute: new Set<string>(noRoute ? (JSON.parse(noRoute.value) as string[]) : []),
  }
}

type TripInputs = Awaited<ReturnType<typeof readTripInputs>>

/** The published trips, each with its legs' routes where `routes` has them. */
export function buildTrips(
  { places, trips, moments }: TripInputs,
  routes: Readonly<Record<string, TripRoute>> = {},
) {
  const resolveLocation = (id: string): TripLocation & Partial<Pick<Place, 'station'>> => {
    const place = places[id]

    if (!place) {
      throw new Error(`Unknown place id "${id}".`)
    }

    return { id, ...place }
  }
  // As the site takes them: visible Moments with their known places, oldest first.
  const visible = moments
    .map((moment) => ({
      dateKey: moment.occurredAt.slice(0, 10),
      locations: moment.places.filter((id) => id in places).map(resolveLocation),
      occurredAt: moment.occurredAt,
      order: moment.order,
      uid: moment.uid,
    }))
    .sort(compareMoments)

  return trips
    .map((trip) => buildTrip(toDefinition(trip), visible, { resolveLocation, routes }))
    .filter((trip) => trip.stops.length > 0)
}

/** Every leg the trips need fetched, by its key: drives not driven back the same way, and trains. */
export function getNeededLegs(trips: ReturnType<typeof buildTrips>, places: Places) {
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
 * `FETCHES_PER_RUN`, and marks deleted the ones no trip needs, in one transaction and
 * none when nothing changed, and keeps what `GET /v1/routes` answers from what it read,
 * so the trips aren't read again for it. A leg with no route is remembered, and one that
 * failed is asked for again the next time. Returns what it did.
 */
export async function fillRoutes(context: WriteContext, fetchRoute: RouteFetcher) {
  const { db } = context
  const inputs = await readTripInputs(db)
  const existing = inputs.routes
  const needed = getNeededLegs(buildTrips(inputs), inputs.places)
  const missing = [...needed].filter(([key]) => !(key in existing) && !inputs.noRoute.has(key))
  const noRoute = new Set([...inputs.noRoute].filter((key) => needed.has(key)))
  let failed = 0
  const answers = await pLimit(FETCHES_AT_ONCE).map(
    missing.slice(0, FETCHES_PER_RUN),
    async ([key, leg]) => {
      try {
        const route = await fetchRoute(leg)

        if (route) {
          return [key, routeSchema.parse(route)] as const
        }

        noRoute.add(key)
        console.warn(
          `No ${leg.by === 'train' ? 'rail line' : 'road'} for ${leg.from.name} → ${leg.to.name}; drawn straight`,
        )
      } catch (caught) {
        failed += 1
        console.error(`Fetching ${leg.from.name} → ${leg.to.name} failed`, caught)
      }

      return undefined
    },
  )
  const fetched = new Map(answers.filter((answer) => answer !== undefined))
  const unused = Object.keys(existing).filter((key) => !needed.has(key))
  // As readTripInputs takes the routes it reads: checked, as the spec has them.
  const routes = { ...existing, ...Object.fromEntries(fetched) } as Record<string, TripRoute>
  const changes = fetched.size > 0 || unused.length > 0
  // A commit takes the next revision, and the answer is of that one.
  const revision = inputs.revision + (changes ? 1 : 0)
  const answer = await answerTripRoutes(buildTrips(inputs, routes), revision)
  const keep = keepTripRoutes(db, answer, revision)

  if (!sameKeys(noRoute, inputs.noRoute)) {
    keep.push(
      db
        .prepare(
          `INSERT INTO meta (key, value) VALUES (?1, ?2)
           ON CONFLICT (key) DO UPDATE SET value = excluded.value`,
        )
        .bind(NO_ROUTE, JSON.stringify([...noRoute].sort())),
    )
  }

  if (changes) {
    await commit(context, [
      ...[...fetched].map(([key, route]) => upsert(db, 'routes', routeRow(key, route))),
      ...unused.map((key) =>
        db
          .prepare(`UPDATE routes SET deleted_at = ?1, rev = ${REVISION} WHERE key = ?2`)
          .bind(context.now, key),
      ),
      ...keep,
    ])
  } else {
    await db.batch(keep)
  }

  return {
    fetched: fetched.size,
    removed: unused.length,
    failed,
    left: Math.max(0, missing.length - FETCHES_PER_RUN),
  }
}

/** `GET /v1/routes`: the JSON text it answers, and an ETag of the routes in it. */
export interface TripRoutes {
  text: string
  etag: string
}

/** What `meta` keeps of `GET /v1/routes`: the trips as JSON text, and their ETag. */
interface KeptRoutes {
  trips: string
  etag: string
}

/**
 * Each published trip's route as the site's overview draws it: drives one after another
 * as one stretch, and each train, plane or ferry as a stretch of its own, drawn dashed.
 * Worked out from every Moment, so the trips are kept in `meta` as the JSON text they
 * are served as, with the revision they are of, and only worked out again once the
 * revision has moved on, by a write the trips see or one that came past the Worker; a
 * write they don't see carries them on to its revision. Its ETag is of the routes alone,
 * so a client that has them hears they haven't changed even when the revision has.
 */
export async function readTripRoutes(db: Database): Promise<TripRoutes> {
  const kept = await db
    .prepare(
      `SELECT CAST(revision.value AS INTEGER) AS revision, trips.value AS trips, etag.value AS etag
       FROM meta AS revision
       JOIN meta AS kept ON kept.key = ?1 AND kept.value = revision.value
       JOIN meta AS trips ON trips.key = ?2
       JOIN meta AS etag ON etag.key = ?3
       WHERE revision.key = 'revision'`,
    )
    .bind(TRIP_ROUTES_REVISION, TRIP_ROUTES, TRIP_ROUTES_ETAG)
    .first<KeptRoutes & { revision: number }>()

  if (kept) {
    return toAnswer(kept, kept.revision)
  }

  const inputs = await readTripInputs(db)
  const answer = await answerTripRoutes(buildTrips(inputs, inputs.routes), inputs.revision)

  await db.batch(keepTripRoutes(db, answer, inputs.revision))

  return toAnswer(answer, inputs.revision)
}

async function answerTripRoutes(
  trips: ReturnType<typeof buildTrips>,
  revision: number,
): Promise<KeptRoutes & { revision: number }> {
  const text = JSON.stringify(
    Object.fromEntries(trips.map((trip) => [trip.id, getTripSummary(trip).segments])),
  )
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  const hex = [...new Uint8Array(digest).slice(0, 16)]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')

  return { trips: text, etag: `"${hex}"`, revision }
}

function toAnswer({ trips, etag }: KeptRoutes, revision: number): TripRoutes {
  return { text: `{"revision":${revision},"trips":${trips}}`, etag }
}

/**
 * Keeps an answer, if the database is still at the revision it is of: a write that came
 * in since has it worked out again.
 */
function keepTripRoutes(db: Database, { trips, etag }: KeptRoutes, revision: number) {
  return [
    [TRIP_ROUTES, trips],
    [TRIP_ROUTES_ETAG, etag],
    [TRIP_ROUTES_REVISION, String(revision)],
  ].map(([key, value]) =>
    db
      .prepare(
        `INSERT INTO meta (key, value) SELECT ?1, ?2 WHERE ${REVISION} = ?3
         ON CONFLICT (key) DO UPDATE SET value = excluded.value`,
      )
      .bind(key, value, revision),
  )
}

function sameKeys(first: ReadonlySet<string>, second: ReadonlySet<string>) {
  return first.size === second.size && [...first].every((key) => second.has(key))
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
