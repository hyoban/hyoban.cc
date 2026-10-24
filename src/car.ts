import {
  findOutings,
  getVehicleCharges,
  getVehicleDays,
  getVehicleSpan,
  readVehicleMonths,
  type Outing,
} from '@/data/vehicle'
import { readPlaces, readTripRecords } from '@/data/store'
import { getMoments } from '@/moments'
import type { Place } from '@hyoban/data-spec'

// What the car recorded, for the year review's driving and the trip list's outings: the
// days driven, from the records in data/zeekr, and the outings found in them against the
// trips and the shown Moments.

export interface CarSummary {
  days: Map<string, number>
  span: { start: string; end: string }
  /** The day each charge started, oldest first. */
  chargeDates: string[]
  outings: Outing[]
}

let summaryPromise: Promise<CarSummary> | undefined

export function getCarSummary() {
  summaryPromise ??= loadCarSummary()
  return summaryPromise
}

async function loadCarSummary(): Promise<CarSummary> {
  const months = readVehicleMonths()
  const days = getVehicleDays(months)
  const charges = getVehicleCharges(months)
  const trips = readTripRecords().map(({ end, id, start, title }) => ({ end, id, start, title }))
  const places = readPlaces()
  const moments = (await getMoments()).map((moment) => ({
    dateKey: moment.dateKey,
    locations: moment.locations.map((location) => toOutingLocation(places[location.id]!)),
  }))

  return {
    chargeDates: charges.map((charge) => charge.startedAt.slice(0, 10)),
    days: new Map(days.map((day) => [day.date, day.distanceMeters])),
    outings: findOutings(days, charges, trips, moments),
    span: getVehicleSpan(months, days),
  }
}

/** Kinds of place that are somewhere to go in their own right, not just in a city. */
const SIGHT_KINDS = new Set<Place['kind']>(['scenic', 'town', 'region'])

/** A place as the city it's in, `南京` for a restaurant there, and its name if it's a sight. */
function toOutingLocation(place: Place) {
  const city =
    place.kind === 'city' || !place.division
      ? place.name
      : (place.division.city ?? place.division.province).replace(/市$/, '')

  return SIGHT_KINDS.has(place.kind) && place.name !== city ? { city, sight: place.name } : { city }
}

const kilometerFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })

/** `3,516 km`. */
export function formatKilometers(meters: number) {
  return `${kilometerFormatter.format(meters / 1000)} km`
}
