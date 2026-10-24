import { vehicleMonthSchema, type VehicleCharge, type VehicleMonth } from '@hyoban/data-spec'
import { dataRoot, listJsonFiles, readJson } from './store.ts'

// The site's view of data/zeekr: each day's distance from the monthly records, and
// the outings found in them, runs of long days that were a trip somewhere. An outing is
// the trip whose dates it overlaps when one was written up, else it's told by the places
// of the Moments on its days. The records stay in the repository; the API doesn't keep them.

export const VEHICLE_ID = 'zeekr-007gt'

/** A day of at least this many metres counts towards an outing. */
export const LONG_DAY_METERS = 150_000
/** And an outing is at least this many metres all told; shorter ones are errands. */
export const OUTING_METERS = 300_000
/** Long days this many days apart or fewer are one outing, a day's rest between them. */
const OUTING_GAP_DAYS = 2

export interface VehicleDistanceDay {
  date: string
  distanceMeters: number
  durationSeconds?: number
}

export interface OutingTrip {
  id: string
  title: string
  start: string
  end: string
}

export interface OutingMoment {
  dateKey: string
  /** Each place as the city it's in, and its own name when it's worth going to on its own. */
  locations: readonly { city: string; sight?: string }[]
}

export interface Outing {
  start: string
  end: string
  distanceMeters: number
  /** Days driven, of the days between start and end. */
  drivenDays: number
  /** DC sessions on its days: charging on the road. */
  fastCharges: number
  trip?: OutingTrip
  /** The cities its days' Moments were in, first seen first, and the sights among them. */
  cities: string[]
  sights: string[]
  momentCount: number
}

export function readVehicleMonths(root = dataRoot): VehicleMonth[] {
  return listJsonFiles(`zeekr/months/${VEHICLE_ID}`, root).map((file) =>
    vehicleMonthSchema.parse(readJson(file, root)),
  )
}

/** Every day with a distance, oldest first; a day the app has no record of is left out. */
export function getVehicleDays(months: VehicleMonth[]): VehicleDistanceDay[] {
  return months
    .flatMap((month) => month.days ?? [])
    .flatMap(({ date, distanceMeters, durationSeconds }) =>
      distanceMeters === undefined
        ? []
        : [{ date, distanceMeters, ...(durationSeconds === undefined ? {} : { durationSeconds }) }],
    )
    .sort((first, second) => first.date.localeCompare(second.date))
}

export function getVehicleCharges(months: VehicleMonth[]): VehicleCharge[] {
  return months
    .flatMap((month) => month.charging?.sessions ?? [])
    .sort((first, second) => first.startedAt.localeCompare(second.startedAt))
}

const DAY = 86_400_000
const dayNumber = (date: string) => Date.parse(`${date}T00:00:00Z`) / DAY

/**
 * The outings in the days, newest first. Long days close together make one; the ones a
 * trip overlaps make one with the trip's dates too, since a trip can rest for days.
 */
export function findOutings(
  days: VehicleDistanceDay[],
  charges: Pick<VehicleCharge, 'startedAt' | 'type'>[],
  trips: OutingTrip[],
  moments: OutingMoment[],
): Outing[] {
  const runs: { start: string; end: string; trip?: OutingTrip }[] = []

  for (const day of days) {
    if (day.distanceMeters < LONG_DAY_METERS) continue
    const last = runs.at(-1)

    if (last && dayNumber(day.date) - dayNumber(last.end) <= OUTING_GAP_DAYS) {
      last.end = day.date
    } else {
      runs.push({ end: day.date, start: day.date })
    }
  }

  // A trip takes the runs it overlaps, and spans its own dates and theirs.
  const merged: typeof runs = []

  for (const run of runs) {
    const trip = trips.find((candidate) => candidate.start <= run.end && run.start <= candidate.end)
    const last = merged.at(-1)

    if (trip && last?.trip === trip) {
      last.end = run.end > last.end ? run.end : last.end
    } else if (trip) {
      merged.push({
        end: run.end > trip.end ? run.end : trip.end,
        start: run.start < trip.start ? run.start : trip.start,
        trip,
      })
    } else {
      merged.push({ ...run })
    }
  }

  return merged
    .map((run): Outing => {
      const inRun = (date: string) => run.start <= date && date <= run.end
      const runDays = days.filter((day) => inRun(day.date))
      const runMoments = moments.filter((moment) => inRun(moment.dateKey))
      const locations = runMoments.flatMap((moment) => moment.locations)

      return {
        cities: [...new Set(locations.map((location) => location.city))],
        distanceMeters: runDays.reduce((total, day) => total + day.distanceMeters, 0),
        drivenDays: runDays.filter((day) => day.distanceMeters > 0).length,
        end: run.end,
        fastCharges: charges.filter(
          (charge) => charge.type === 'DC' && inRun(charge.startedAt.slice(0, 10)),
        ).length,
        momentCount: runMoments.length,
        sights: [...new Set(locations.flatMap((location) => location.sight ?? []))],
        start: run.start,
        ...(run.trip ? { trip: run.trip } : {}),
      }
    })
    .filter((outing) => outing.trip || outing.distanceMeters >= OUTING_METERS)
    .reverse()
}

/**
 * A trip's days as the car recorded them, when the car drove all of it: every day within
 * the records' span, and no leg by plane or train, which leaves the car at home or at an
 * airport while a rental drives the routes. A ferry carries the car along.
 */
export function getTripOdometer(
  trip: { start: string; end: string; legs: readonly { by: string }[] },
  days: VehicleDistanceDay[],
  span: { start: string; end: string },
) {
  if (
    trip.start < span.start ||
    trip.end > span.end ||
    trip.legs.some((leg) => leg.by === 'plane' || leg.by === 'train')
  ) {
    return undefined
  }

  const tripDays = days.filter((day) => trip.start <= day.date && day.date <= trip.end)

  return {
    days: new Map(tripDays.map((day) => [day.date, day])),
    distanceMeters: tripDays.reduce((total, day) => total + day.distanceMeters, 0),
    durationSeconds: tripDays.reduce((total, day) => total + (day.durationSeconds ?? 0), 0),
  }
}

/** How dark a day's square is, 0 for a day parked to 4 for one of 300 km or more. */
export function getDistanceLevel(distanceMeters: number) {
  if (distanceMeters === 0) return 0
  if (distanceMeters < 50_000) return 1
  if (distanceMeters < LONG_DAY_METERS) return 2
  if (distanceMeters < OUTING_METERS) return 3
  return 4
}

/** The days the records cover: from the car's first day to the latest capture. */
export function getVehicleSpan(months: VehicleMonth[], days: VehicleDistanceDay[]) {
  const captured = months.map((month) => month.capturedAt.slice(0, 10)).sort()
  return { end: captured.at(-1)!, start: days[0]!.date }
}

export interface HeatmapCell {
  date: string
  /** Missing outside the records' span, before the car or after the latest capture. */
  distanceMeters?: number
}

/**
 * A year as weeks, Monday first, the way a contribution graph draws it: the first week
 * starts on the Monday on or before January 1, and days outside the year are null. The
 * app leaves out the days the car wasn't woken, so within the span a missing day is 0.
 */
export function getYearWeeks(
  year: number,
  days: Map<string, number>,
  span: { start: string; end: string },
): (HeatmapCell | null)[][] {
  const first = Date.UTC(year, 0, 1)
  const last = Date.UTC(year, 11, 31)
  const offset = (new Date(first).getUTCDay() + 6) % 7
  const weeks: (HeatmapCell | null)[][] = []

  for (let time = first - offset * DAY; time <= last; time += 7 * DAY) {
    weeks.push(
      Array.from({ length: 7 }, (_, weekday) => {
        const at = time + weekday * DAY
        if (at < first || at > last) return null
        const date = new Date(at).toISOString().slice(0, 10)
        return date < span.start || date > span.end
          ? { date }
          : { date, distanceMeters: days.get(date) ?? 0 }
      }),
    )
  }

  return weeks
}
