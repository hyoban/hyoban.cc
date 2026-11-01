import { z } from 'zod'

import { getMediaDirectory, momentMediaSchema } from './media.ts'
import {
  count,
  dateKey,
  foodVerdictSchema,
  id,
  occurredAt,
  price,
  timestamp,
  ulid,
  unique,
} from './primitives.ts'

// The first shape of the data (schema version 2): Moments, trips with their waypoints, and
// the devices kept apart. `data/` and D1 hold entries and collections now (`./entry.ts`); this
// stays for what still reads a Moment or a trip, through `entryToMoment` and
// `collectionToTrip`, and for `vp run data:migrate` to move a copy over and back.

/**
 * What was eaten or drunk in a Moment, whatever else it is about: a trip's day can name its
 * meal too. Told by the Moment rather than its place, since a place is eaten at more
 * than once and most meals are only placed in their city.
 */
export const momentFoodSchema = z.looseObject({
  /** The dishes and drinks as the words name them, like 沙茶面 or 卡旺卡. */
  dishes: z.array(z.string().min(1)).default([]),
  verdict: foodVerdictSchema.optional(),
  /** What the meal cost all told, and how many it fed. */
  bill: z.looseObject({ amount: price, people: count.optional() }).optional(),
})

export const momentRecordSchema = z
  .looseObject({
    uid: ulid,
    occurredAt,
    /**
     * The place it was at, if any: one at most, so each place's Moments, photos and map
     * drawer hold only what happened there. A day at several places is one Moment each.
     * Still a list, as it was when a Moment could name several.
     */
    places: z.array(id).max(1, 'A Moment is at one place at most.').default([]),
    /** The words as written, with their line breaks. */
    text: z.string(),
    hidden: z.boolean().default(false),
    media: z
      .array(momentMediaSchema)
      .refine((media) => unique(media.map((item) => item.uid)), 'Duplicate media uid.')
      .default([]),
    food: momentFoodSchema.optional(),
    /** The trip it belongs to; without one, the trip whose dates include it. */
    trip: id.optional(),
    /**
     * Where it sits among its day's Moments when their times don't say, lower first; one
     * without comes after those with one, the one made first first. Only a way to sort:
     * it names nothing, and needn't be unique or follow on.
     */
    order: count.optional(),
    /**
     * Set when the Moment is published: when it was. Nothing about it names the Moment,
     * which is its uid, so its day, places and words can all change afterwards.
     */
    published: z.looseObject({ at: timestamp }).optional(),
    createdAt: timestamp,
    updatedAt: timestamp,
  })
  .superRefine((moment, context) => {
    // Every uploaded object of the Moment lives under its uid (see `getMediaKey`).
    const keys: string[] = []
    const collectKeys = (value: unknown) => {
      if (value && typeof value === 'object') {
        for (const [name, entry] of Object.entries(value)) {
          if (name === 'key' && typeof entry === 'string') {
            keys.push(entry)
          } else {
            collectKeys(entry)
          }
        }
      }
    }

    for (const media of moment.media) {
      collectKeys(media.published)
    }

    for (const key of keys) {
      if (!key.startsWith(getMediaDirectory(moment.uid))) {
        context.addIssue({
          code: 'custom',
          message: `Media key outside the Moment's directory: ${key}`,
        })
      }
    }
  })

/** Whether the site shows a Moment: published and not hidden. */
export function isMomentShown(moment: Pick<MomentRecord, 'published' | 'hidden'>) {
  return Boolean(moment.published) && !moment.hidden
}

const travelMode = z.enum(['ferry', 'plane', 'train'])

export const tripRecordSchema = z
  .looseObject({
    id,
    title: z.string().min(1),
    start: dateKey,
    end: dateKey,
    /** A track from apps/web/src/data/music.ts. */
    music: id.optional(),
    note: z.string().optional(),
    /** Places passed without a Moment; `at` can be left out to follow the one before. */
    waypoints: z.array(z.looseObject({ at: occurredAt.optional(), place: id })).default([]),
    /** Legs taken by ferry, plane or train rather than driven. */
    straightLegs: z.array(z.looseObject({ from: id, to: id, by: travelMode })).default([]),
    /** Places driven back the way they came. */
    sameWayBack: z.array(z.looseObject({ from: id, to: id })).default([]),
    /** Photos for the intro and summary, best first. */
    covers: z.array(z.looseObject({ moment: ulid, media: ulid })).default([]),
    createdAt: timestamp,
    updatedAt: timestamp,
  })
  .refine((trip) => trip.start <= trip.end, 'The trip ends before it starts.')

/** A device as the first shape kept it, by its id; an entry's `device` part now. */
export const deviceSchema = z
  .looseObject({
    name: z.string().min(1),
    /** What kind of thing it is, like 手机 or 电脑; the use page gives each its own lane. */
    category: z.string().min(1),
    bought: z.looseObject({ on: dateKey, price }),
    sold: z.looseObject({ on: dateKey, price }).optional(),
    /** Still owned but no longer used. */
    idle: z.boolean().default(false),
    /** A picture of it on a clear background, its key in the bucket, like `devices/<id>.webp`. */
    image: z.string().min(1).optional(),
    note: z.string().optional(),
  })
  .refine((device) => !device.sold || device.bought.on <= device.sold.on, 'Sold before bought.')

export const devicesSchema = z.record(id, deviceSchema)

export type MomentRecord = z.output<typeof momentRecordSchema>
export type MomentRecordInput = z.input<typeof momentRecordSchema>
export type MomentMedia = MomentRecord['media'][number]
export type MomentFood = z.output<typeof momentFoodSchema>
export type TripRecord = z.output<typeof tripRecordSchema>
export type TripRecordInput = z.input<typeof tripRecordSchema>
export type Device = z.output<typeof deviceSchema>
export type Devices = z.output<typeof devicesSchema>

/** The directory a Moment's file sits in under `data/moments`: its year and month. */
export function getMomentDirectory(occurredAt: string) {
  return `${occurredAt.slice(0, 4)}/${occurredAt.slice(5, 7)}`
}

/** A Moment's file under `data/`, like `moments/2026/10/01K7….json`. */
export function getMomentFile(moment: Pick<MomentRecord, 'occurredAt' | 'uid'>) {
  return `moments/${getMomentDirectory(moment.occurredAt)}/${moment.uid}.json`
}
