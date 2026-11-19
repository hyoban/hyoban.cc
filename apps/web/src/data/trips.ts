import { defineTrips, type TripDefinition } from '@hyoban/trip-map'
import type { TripRecord } from '@hyoban/data-spec'
import { readTripIds, readTripRecords } from './store.ts'

// Each trip (a collection in data/collections, or data/trips in the first layout) plays back every located Moment between `start` and `end`
// (inclusive) at its address by its year and title, /trip/2026/xuzhou-zhoumo-xing/ (trip-slugs.ts). `waypoints` add places the route passed through without a
// Moment. The API fetches each leg's route after a write, so the route follows roads.

// Each by its id on the site, its year and its title in pinyin (trip-slugs.ts).
const ids = readTripIds()
const records = readTripRecords()
  .map((record) => ({ ...record, id: ids.get(record.id) ?? record.id }))
  .sort((first, second) => second.start.localeCompare(first.start))

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
    ...(record.planned ? { planned: true as const } : {}),
    sameWayBack: record.sameWayBack.map(({ from, to }) => [from, to] as const),
    start: record.start,
    ...(record.startAt === undefined ? {} : { startAt: record.startAt }),
    ...(record.endAt === undefined ? {} : { endAt: record.endAt }),
    straightLegs: record.straightLegs.map(({ from, to, by }) => [from, to, by] as const),
    title: record.title,
    waypoints: record.waypoints.map(({ at, place }) => ({
      ...(at === undefined ? {} : { at }),
      location: place,
    })),
  }
}
