import { defineTrips, type TripDefinition } from '@hyoban/trip-map'
import type { TripRecord } from '@hyoban/data-spec'
import { readTripRecords } from './store.ts'

// Each trip in data/trips plays back every located Moment between `start` and `end`
// (inclusive) at /trip/<id>/. `waypoints` add places the route passed through without a
// Moment. The API fetches each leg's route after a write, so the route follows roads.

const records = readTripRecords().sort((first, second) => second.start.localeCompare(first.start))

export const trips = defineTrips(records.map(toDefinition))

export type TripCover = TripRecord['covers'][number]

/** Each trip's cover photos, as Moment and media uids, newest trip first. */
export const tripCovers = new Map(records.map((record) => [record.id, record.covers]))

/** The trip-map model's view of a record: legs as tuples, and no covers (they're uids). */
function toDefinition(record: TripRecord): TripDefinition {
  return {
    end: record.end,
    id: record.id,
    ...(record.music === undefined ? {} : { music: record.music }),
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
