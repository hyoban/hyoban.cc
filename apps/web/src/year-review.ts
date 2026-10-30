import type { FoodVerdict } from '@hyoban/data-spec'
import { buildFogOfWar, getShortPlaceName, type FogRegions } from '@hyoban/trip-map'
import type { Moment, MomentTrip } from './moments/index.ts'

// A year at a glance, for /<year>/, or every year at once, for /life/: what its Moments,
// trips and cities add up to. Worked out from what the pages already have, so it needs
// nothing kept apart.

type ReviewMoment = Pick<
  Moment,
  'dateKey' | 'id' | 'locations' | 'media' | 'occurredAt' | 'occurredLabel' | 'text' | 'topic'
>
type ReviewTrip = Pick<
  MomentTrip,
  'dayCount' | 'drivingKm' | 'end' | 'id' | 'placeCount' | 'start' | 'title'
>

export type YearPlace = { id: string; name: string; count: number }

export type YearReview<M, T> = {
  /** The year, or none for every year. */
  year?: number
  moments: M[]
  photoCount: number
  videoCount: number
  /** The days with a Moment. */
  dayCount: number
  /** The longest run of days in a row with a Moment, the first such when tied. */
  streak: { start: string; end: string; days: number }
  /** The day with the most Moments, the first such when tied. */
  busiestDay: { dateKey: string; count: number }
  trips: T[]
  tripDays: number
  drivingKm: number
  /** The cities lit, most Moments first, and those lit for the first time this year. */
  cities: { lit: YearPlace[]; provinces: number; newNames: string[] }
  /** The cities checked in at for the first time, by their first Moment, in the order the year reached them. */
  firstVisits: { name: string; dateKey: string }[]
}

/** The years with a Moment, oldest first. */
export function getReviewYears(moments: readonly Pick<Moment, 'dateKey'>[]) {
  return [
    ...new Set(moments.map((moment) => Number.parseInt(moment.dateKey.slice(0, 4), 10))),
  ].sort((first, second) => first - second)
}

/**
 * `moments` are every Moment, oldest first, so the year can tell what it saw for the first
 * time; without a year, it's every year, and all it saw it saw for the first time.
 */
export function buildYearReview<M extends ReviewMoment, T extends ReviewTrip>(
  year: number | undefined,
  moments: readonly M[],
  trips: readonly T[],
  cities: FogRegions,
): YearReview<M, T> {
  const prefix = year === undefined ? '' : String(year)
  const yearMoments = moments.filter((moment) => moment.dateKey.startsWith(prefix))
  const yearTrips = trips
    .filter((trip) => trip.start.startsWith(prefix))
    .sort((first, second) => first.start.localeCompare(second.start))

  const media = yearMoments.flatMap((moment) => moment.media)
  const days = countBy(yearMoments, (moment) => moment.dateKey)
  const dayKeys = [...days.keys()].sort()

  const fog = litCities(cities, yearMoments)
  const litBefore = new Set(
    litCities(
      cities,
      moments.filter((moment) => moment.dateKey < prefix),
    ).lit,
  )
  const names = new Map(
    cities.features.map(({ properties }) => [properties.id, getCityName(properties.name)]),
  )
  const lit = fog.lit
    .map((id) => ({ id, name: names.get(id) ?? id, count: fog.weights[id] ?? 0 }))
    .sort((first, second) => second.count - first.count)

  const firstVisits = new Map<string, { name: string; dateKey: string }>()
  for (const moment of yearMoments) {
    for (const location of moment.locations) {
      const [id] = buildFogOfWar(cities, [location]).lit
      if (id && !litBefore.has(id) && !firstVisits.has(id)) {
        firstVisits.set(id, { name: names.get(id) ?? id, dateKey: moment.dateKey })
      }
    }
  }

  return {
    ...(year === undefined ? {} : { year }),
    moments: yearMoments,
    photoCount: media.filter((item) => item.type !== 'video').length,
    videoCount: media.filter((item) => item.type === 'video').length,
    dayCount: days.size,
    streak: getLongestStreak(dayKeys),
    busiestDay: (() => {
      const [dateKey, count] = maxBy([...days], ([, count]) => count) ?? ['', 0]
      return { dateKey, count }
    })(),
    trips: yearTrips,
    tripDays: yearTrips.reduce((total, trip) => total + trip.dayCount, 0),
    drivingKm: yearTrips.reduce((total, trip) => total + trip.drivingKm, 0),
    cities: {
      lit,
      provinces: fog.provinces,
      newNames: lit.filter(({ id }) => !litBefore.has(id)).map(({ name }) => name),
    },
    firstVisits: [...firstVisits.values()],
  }
}

/** A meal the year's Moments tell of: what was eaten, or else where, and how it was. */
export type YearMeal<M> = { moment: M; name: string; verdict?: FoodVerdict }

/**
 * The meals among these Moments, in their order: each Moment whose food names a dish or
 * a verdict, called by its dishes, or by its place when it names none.
 */
export function getYearMeals<M extends Pick<Moment, 'food' | 'locations'>>(
  moments: readonly M[],
): YearMeal<M>[] {
  return moments.flatMap((moment) => {
    const { food } = moment
    const place = moment.locations[0]
    const name = food?.dishes.join('、') || (place && getShortPlaceName(place.name))
    if (!food || !name || (food.dishes.length === 0 && !food.verdict)) return []
    return [{ moment, name, ...(food.verdict && { verdict: food.verdict }) }]
  })
}

/** The longest run of consecutive days among sorted `YYYY-MM-DD` keys. */
export function getLongestStreak(dayKeys: readonly string[]) {
  let best = { start: dayKeys[0] ?? '', end: dayKeys[0] ?? '', days: dayKeys.length > 0 ? 1 : 0 }
  let start = 0

  for (let index = 1; index <= dayKeys.length; index++) {
    if (index < dayKeys.length && daysBetween(dayKeys[index - 1]!, dayKeys[index]!) === 1) continue
    if (index - start > best.days) {
      best = { start: dayKeys[start]!, end: dayKeys[index - 1]!, days: index - start }
    }
    start = index
  }

  return best
}

/** The cities the Moments were written in, as the map's fog of war lights them. */
function litCities(cities: FogRegions, moments: readonly ReviewMoment[]) {
  return buildFogOfWar(
    cities,
    moments.flatMap((moment) =>
      moment.locations.map(({ latitude, longitude }) => ({ latitude, longitude })),
    ),
  )
}

// The peoples an autonomous prefecture can be named for, as `迪庆藏族自治州` names its.
const PEOPLES =
  '藏|白|彝|苗|侗|朝鲜|哈尼|傣|景颇|傈僳|壮|土家|回|蒙古|哈萨克|柯尔克孜|布依|羌|黎|纳西|拉祜|佤'

/**
 * A city as people say it: `苏州市` is `苏州`, `迪庆藏族自治州` is `迪庆`, `阿里地区` is
 * `阿里`; a name that'd be one character keeps its suffix.
 */
export function getCityName(name: string) {
  const short = name
    .replace(new RegExp(`(?:(?:${PEOPLES})族)+自治州$`), '')
    .replace(/(?:市|地区)$/, '')
  return short.length > 1 ? short : name
}

function countBy<T, K>(items: readonly T[], key: (item: T) => K) {
  const counts = new Map<K, number>()
  for (const item of items) counts.set(key(item), (counts.get(key(item)) ?? 0) + 1)
  return counts
}

/** The first item with the largest `value`. */
function maxBy<T>(items: readonly T[], value: (item: T) => number) {
  let best: T | undefined
  for (const item of items) if (best === undefined || value(item) > value(best)) best = item
  return best
}

function daysBetween(first: string, second: string) {
  return Math.round(
    (Date.parse(`${second}T00:00:00Z`) - Date.parse(`${first}T00:00:00Z`)) / 86_400_000,
  )
}
