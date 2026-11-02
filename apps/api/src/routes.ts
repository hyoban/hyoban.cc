import {
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
import {
  collectionSchema,
  collectionToTrip,
  entryToMoment,
  isMomentEntry,
} from '@hyoban/data-spec/entry'
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
  type Database,
} from './database.ts'
import { REVISION, routeRow } from './rows.ts'
import { commit, toEntry, upsert, type WriteContext } from './writes.ts'

// The trips' routes. Each leg between two consecutive places of a trip is driven along
// the road FOSSGIS's public Valhalla finds, or for a train leg along the rail line
// OpenRailRouting's public demo finds between the places' stations, neither needing a key,
// so the app can route its own trips the same way; a ferry or a flight
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

/**
 * How many legs are fetched at once: few, as the public routers ask, and still enough for
 * a run to stay inside `waitUntil`'s time.
 */
const FETCHES_AT_ONCE = 2

/**
 * Where `meta` keeps the keys of the legs the routers found no way along, as a drive
 * across the sea, so they are drawn straight and not asked for again; a moved place is
 * another leg, and asked for.
 */
const NO_ROUTE = 'routes_none'

/**
 * Where `meta` keeps the keys of the legs to route again, over the routes they have, as
 * after a change of router: each fill takes its batch from them as from the missing ones,
 * and a leg keeps its old route until its new one comes.
 */
const AGAIN = 'routes_again'

/** What the trips are built from, read at one revision. */
export async function readTripInputs(db: Database) {
  const [revision, places, collections, entryRows, routes, none, again] = await readAll(db, [
    revisionQuery(db),
    placesQuery(db),
    db.prepare(`SELECT record FROM collections WHERE deleted_at IS NULL ORDER BY start_day, key`),
    // Moments for the stops, and marks for the waypoints.
    db.prepare(
      `SELECT record FROM entries WHERE deleted_at IS NULL AND kind IN ('moment', 'mark')`,
    ),
    routesQuery(db),
    db.prepare(`SELECT value FROM meta WHERE key = ?1`).bind(NO_ROUTE),
    db.prepare(`SELECT value FROM meta WHERE key = ?1`).bind(AGAIN),
  ])
  const [noRoute] = none as { value: string }[]
  const [routeAgain] = again as { value: string }[]
  const entries = (entryRows as { record: string }[]).map((row) => toEntry(row))

  return {
    revision: toRevision(revision),
    places: placesSchema.parse(toRecordsByKey(places)),
    trips: (collections as { record: string }[]).map((row) =>
      tripRecordSchema.parse(
        collectionToTrip(collectionSchema.parse(JSON.parse(row.record)), entries),
      ),
    ),
    // As the site takes them: Moments published and not private.
    moments: entries
      .filter((entry) => isMomentEntry(entry) && entry.published && entry.visibility !== 'private')
      .map((entry) => entryToMoment(entry)),
    routes: toRecordsByKey(routes) as Record<string, TripRoute>,
    noRoute: new Set<string>(noRoute ? (JSON.parse(noRoute.value) as string[]) : []),
    again: new Set<string>(routeAgain ? (JSON.parse(routeAgain.value) as string[]) : []),
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
 * none when nothing changed, and keeps what `GET /v2/routes` answers from what it read,
 * so the trips aren't read again for it. A leg with no route is remembered, and one that
 * failed is asked for again the next time. Returns what it did.
 */
export async function fillRoutes(context: WriteContext, fetchRoute: RouteFetcher) {
  const { db } = context
  const inputs = await readTripInputs(db)
  const existing = inputs.routes
  const needed = getNeededLegs(buildTrips(inputs), inputs.places)
  // The legs with no route first, then the ones to route again.
  const missing = [...needed]
    .filter(([key]) => inputs.again.has(key) || (!(key in existing) && !inputs.noRoute.has(key)))
    .sort(([first], [second]) => Number(first in existing) - Number(second in existing))
  const noRoute = new Set([...inputs.noRoute].filter((key) => needed.has(key)))
  const again = new Set([...inputs.again].filter((key) => needed.has(key)))
  // A leg routed again that now has no way along it loses its old route.
  const lost: string[] = []
  let failed = 0
  const answers = await pLimit(FETCHES_AT_ONCE).map(
    missing.slice(0, FETCHES_PER_RUN),
    async ([key, leg]) => {
      try {
        const route = await fetchRoute(leg)

        again.delete(key)

        if (route) {
          return [key, routeSchema.parse(route)] as const
        }

        if (key in existing) {
          lost.push(key)
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
  const unused = [...Object.keys(existing).filter((key) => !needed.has(key)), ...lost]
  // As readTripInputs takes the routes it reads: checked, as the spec has them.
  const routes = Object.fromEntries(
    Object.entries({ ...existing, ...Object.fromEntries(fetched) }).filter(
      ([key]) => !lost.includes(key),
    ),
  ) as Record<string, TripRoute>
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

  if (!sameKeys(again, inputs.again)) {
    keep.push(
      db
        .prepare(
          `INSERT INTO meta (key, value) VALUES (?1, ?2)
           ON CONFLICT (key) DO UPDATE SET value = excluded.value`,
        )
        .bind(AGAIN, JSON.stringify([...again].sort())),
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

/**
 * Has every drive leg the trips have routed again by the fills to come, as after the
 * drive router changed; returns how many.
 */
export async function routeDrivesAgain(db: Database) {
  const inputs = await readTripInputs(db)
  const drives = [...getNeededLegs(buildTrips(inputs), inputs.places)]
    .filter(([key, leg]) => leg.by === 'car' && key in inputs.routes)
    .map(([key]) => key)

  await db
    .prepare(
      `INSERT INTO meta (key, value) VALUES (?1, ?2)
       ON CONFLICT (key) DO UPDATE SET value = excluded.value`,
    )
    .bind(AGAIN, JSON.stringify(drives.sort()))
    .run()

  return drives.length
}

/** `GET /v2/routes`: the JSON text it answers, and an ETag of the routes in it. */
export interface TripRoutes {
  text: string
  etag: string
}

/** What `meta` keeps of `GET /v2/routes`: the trips as JSON text, and their ETag. */
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

/** Fetches drive legs from Valhalla and train legs from OpenRailRouting. */
export function createRouteFetcher(fetcher: typeof fetch = fetch): RouteFetcher {
  return async (leg) => (leg.by === 'train' ? fetchRail(leg, fetcher) : fetchDrive(leg, fetcher))
}

/** Both public servers ask to be told who is calling. */
const USER_AGENT = 'hyoban.cc trip routes'

/**
 * Valhalla's answers for a leg it will never route: no road near a place (171), longer
 * than its 1,500 km limit (154), or no path between them (442, 443).
 */
const NO_PATH = new Set([154, 171, 442, 443])

async function fetchDrive({ from, to }: Leg, fetcher: typeof fetch) {
  const url = new URL('https://valhalla1.openstreetmap.de/route')
  url.searchParams.set(
    'json',
    JSON.stringify({
      locations: [
        { lat: from.latitude, lon: from.longitude },
        { lat: to.latitude, lon: to.longitude },
      ],
      costing: 'auto',
      directions_type: 'none',
    }),
  )

  const response = await fetcher(url, { headers: { 'User-Agent': USER_AGENT } })
  const body = (await response.json()) as {
    error_code?: number
    error?: string
    trip?: { legs: { shape: string }[]; summary: { length: number; time: number } }
  }

  if (body.error_code !== undefined && NO_PATH.has(body.error_code)) {
    return undefined
  }

  const [leg] = body.trip?.legs ?? []

  if (!response.ok || !body.trip || !leg) {
    throw new Error(`Valhalla answered ${response.status}: ${body.error ?? body.error_code}`)
  }

  return {
    coordinates: simplify(decodePolyline(leg.shape)),
    distance: Math.round(body.trip.summary.length * 1000),
    duration: Math.round(body.trip.summary.time),
  }
}

/** A line in Valhalla's encoded polyline, six decimal places, as (longitude, latitude). */
export function decodePolyline(text: string): [number, number][] {
  const line: [number, number][] = []
  let index = 0
  let latitude = 0
  let longitude = 0
  const next = () => {
    let result = 0
    let shift = 0
    let byte: number

    do {
      byte = text.charCodeAt(index++) - 63
      result |= (byte & 0x1f) << shift
      shift += 5
    } while (byte >= 0x20)

    return result & 1 ? ~(result >> 1) : result >> 1
  }

  while (index < text.length) {
    latitude += next()
    longitude += next()
    line.push([longitude / 1e6, latitude / 1e6])
  }

  return line
}

async function fetchRail({ from, to }: Leg, fetcher: typeof fetch) {
  const url = new URL('https://routing.openrailrouting.org/route')
  url.searchParams.append('point', `${from.latitude},${from.longitude}`)
  url.searchParams.append('point', `${to.latitude},${to.longitude}`)
  url.searchParams.set('profile', 'tgv_all')
  url.searchParams.set('points_encoded', 'false')
  url.searchParams.set('instructions', 'false')

  // The demo turns away requests without a User-Agent of their own.
  const response = await fetcher(url, { headers: { 'User-Agent': USER_AGENT } })
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
