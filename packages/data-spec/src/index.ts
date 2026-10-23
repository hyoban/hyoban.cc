import { z } from 'zod'

// The data spec: the format the recording app stores, the repository commits under
// `data/` and the site reads. Every object is loose, so a field one side doesn't know
// yet travels through the other untouched; `pnpm data:schema` writes these schemas out
// as JSON Schema for the app. Changes only add optional fields, or tighten a rule every
// stored record already keeps (a Moment at one place at most); any other change bumps
// SCHEMA_VERSION.

export const SCHEMA_VERSION = 1

/** A kebab-case id: a place, a trip, a music track or a Moment's slug. */
export const ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
/** A published Moment's id: its date, its order that day and its slug. */
export const MOMENT_ID_PATTERN = /^(\d{4})\/(\d{2})\/(\d{2})-(\d{2})-([a-z0-9]+(?:-[a-z0-9]+)*)$/
const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/
const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})$/

const id = z.string().regex(ID_PATTERN, 'Use a kebab-case id.')
const ulid = z.string().regex(ULID_PATTERN, 'Use a ULID.')
const dateKey = z.string().regex(DATE_KEY_PATTERN, 'Use a YYYY-MM-DD date.')
const timestamp = z
  .string()
  .regex(TIMESTAMP_PATTERN, 'Use an ISO timestamp with an explicit offset.')
  .refine((value) => !Number.isNaN(Date.parse(value)), 'Use a real date and time.')
/** When something happened: a timestamp with its offset, or just the day. */
const occurredAt = z.union([dateKey, timestamp])
const count = z.int().positive()
const unique = (values: readonly string[]) => new Set(values).size === values.length

const r2Object = z.looseObject({
  /** The object's key in the bucket, like `moments/2026/10/01-02-ferry-sunset/ferry-sunset-live-1.webp`. */
  key: z.string().min(1),
  contentType: z.string().min(1),
  bytes: count,
  etag: z.string().regex(/^[0-9a-f]{32}$/, "Use the object's MD5 ETag."),
})

/** An uploaded image with its responsive variants. */
export const publishedImageSchema = r2Object.extend({
  contentType: z.string().startsWith('image/'),
  width: count,
  height: count,
  variants: z.array(r2Object.extend({ width: count, height: count })).default([]),
  /**
   * Kept as uploaded rather than optimized, with no variants: the site has Cloudflare
   * Images resize it. What the API publishes; the create-moment skill optimizes instead.
   */
  original: z.boolean().optional(),
})

/** An uploaded video. */
export const publishedVideoSchema = r2Object.extend({
  contentType: z.string().startsWith('video/'),
  /**
   * Kept as uploaded rather than transcoded, with no poster: the site takes its first
   * frame from Cloudflare's Media Transformations. What the API publishes.
   */
  original: z.boolean().optional(),
})

/** Where a media item lives on the phone; only ever in the app's own store. */
const localMediaSchema = z.looseObject({
  uri: z.string().min(1),
  videoUri: z.string().min(1).optional(),
  sha256: z
    .string()
    .regex(/^[0-9a-f]{64}$/)
    .optional(),
  copied: z.boolean().default(false),
})

const mediaBase = {
  uid: ulid,
  /** English alt text; empty until someone writes it. */
  alt: z.string().default(''),
  takenAt: timestamp.optional(),
  /**
   * Kept for the record, a screenshot or a flat tyre, rather than worth showing off: it
   * shows in its own Moment but is never picked for a cover, a collage or a share card.
   */
  utility: z.boolean().optional(),
  local: localMediaSchema.optional(),
}

export const momentMediaSchema = z.discriminatedUnion('type', [
  z.looseObject({
    ...mediaBase,
    type: z.literal('image'),
    width: count,
    height: count,
    published: publishedImageSchema.optional(),
  }),
  z.looseObject({
    ...mediaBase,
    type: z.literal('live-photo'),
    width: count,
    height: count,
    published: publishedImageSchema.extend({ video: publishedVideoSchema }).optional(),
  }),
  z.looseObject({
    ...mediaBase,
    type: z.literal('video'),
    width: count.optional(),
    height: count.optional(),
    published: publishedVideoSchema.extend({ poster: publishedImageSchema.optional() }).optional(),
  }),
])

/**
 * What a Moment is about: code and software; a trip or somewhere been to; eating; the car,
 * its charging and upkeep; a camera and taking photos; or the rest of life.
 */
export const momentTopicSchema = z.enum(['tech', 'travel', 'food', 'car', 'photo', 'life'])

/** Whether the site shows a Moment: published, not hidden and not about tech. */
export function isMomentShown(moment: Pick<MomentRecord, 'published' | 'hidden' | 'topic'>) {
  return Boolean(moment.published) && !moment.hidden && moment.topic !== 'tech'
}

/** A price in yuan, as paid or got back. */
const price = z.number().nonnegative()

/** How something eaten turned out: worth having again, passable, or not. */
export const foodVerdictSchema = z.enum(['good', 'ok', 'bad'])

/**
 * What was eaten or drunk in a Moment, whatever its topic: a trip's day can name its
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
    /**
     * What it is mostly about. A tech one is never shown, like a hidden one; `hidden` is
     * kept for whatever else shouldn't be, a Moment posted twice.
     */
    topic: momentTopicSchema.optional(),
    media: z
      .array(momentMediaSchema)
      .refine((media) => unique(media.map((item) => item.uid)), 'Duplicate media uid.')
      .default([]),
    food: momentFoodSchema.optional(),
    /** The trip it belongs to; without one, the trip whose dates include it. */
    trip: id.optional(),
    /** Set when the Moment is published, and never changed after. */
    published: z
      .looseObject({
        id: z.string().regex(MOMENT_ID_PATTERN, 'Use a YYYY/MM/DD-NN-slug id.'),
        slug: id,
        order: count,
        at: timestamp,
      })
      .optional(),
    createdAt: timestamp,
    updatedAt: timestamp,
  })
  .superRefine((moment, context) => {
    if (!moment.published) {
      return
    }

    const match = moment.published.id.match(MOMENT_ID_PATTERN)!
    const [, year, month, day, order, slug] = match

    if (`${year}-${month}-${day}` !== moment.occurredAt.slice(0, 10)) {
      context.addIssue({ code: 'custom', message: 'The published id is on another day.' })
    }

    if (Number.parseInt(order!, 10) !== moment.published.order || slug !== moment.published.slug) {
      context.addIssue({
        code: 'custom',
        message: 'The published id disagrees with its order or slug.',
      })
    }

    // Every uploaded object of the Moment lives under its published id.
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
      if (!key.startsWith(`moments/${moment.published.id}/`)) {
        context.addIssue({
          code: 'custom',
          message: `Media key outside the Moment's directory: ${key}`,
        })
      }
    }
  })

const point = {
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
}

/**
 * What sort of place it is: a city; a county, town or village passed through; an area
 * too wide for one point, like a sea; somewhere to see, eat, shop or stay; or a station,
 * airport, port or car park.
 */
export const placeKindSchema = z.enum([
  'city',
  'town',
  'region',
  'scenic',
  'food',
  'shopping',
  'stay',
  'transport',
])

export const placeSchema = z.looseObject({
  name: z.string().min(1),
  ...point,
  kind: placeKindSchema.optional(),
  /**
   * The administrative division the point falls in, from Aliyun DataV's boundaries, as
   * `locateDivision` in `./division.ts` finds it: the deepest adcode and its names. A city stops above
   * the district and a region at its province; Beijing, Shanghai, Tianjin, Chongqing and
   * Hong Kong are their own city. Left out where no division holds it, like a point at sea.
   */
  division: z
    .looseObject({
      code: z.string().regex(/^\d{6}$/, 'Use a six-digit adcode.'),
      province: z.string().min(1),
      city: z.string().min(1).optional(),
      district: z.string().min(1).optional(),
    })
    .optional(),
  /** A note to self; the site doesn't show it. */
  note: z.string().optional(),
  /**
   * Where a train leg to or from the place starts or ends: rail lines run from stations,
   * and a city's centre may be nearer some other track. Without one, the place itself.
   */
  station: z.looseObject({ name: z.string().min(1), ...point }).optional(),
  createdAt: timestamp.optional(),
})

export const placesSchema = z.record(id, placeSchema)

const travelMode = z.enum(['ferry', 'plane', 'train'])

export const tripRecordSchema = z
  .looseObject({
    id,
    title: z.string().min(1),
    start: dateKey,
    end: dateKey,
    /** A track from src/data/music.ts. */
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

/**
 * A driving route between two places, or a rail line for a train leg, as the API fetched
 * it: its points as `[longitude, latitude]`, simplified to about 30 m.
 */
export const routeSchema = z.looseObject({
  coordinates: z.array(z.tuple([z.number(), z.number()])).min(2),
  /** Metres along the route. */
  distance: z.number().nonnegative(),
  /** Seconds of driving without traffic, as Mapbox estimates it. */
  duration: z.number().nonnegative().optional(),
})

/**
 * Every leg's route by its key: `lng,lat;lng,lat` between two places' points, and
 * `train:` before that for a rail line.
 */
export const routesSchema = z.record(z.string().min(1), routeSchema)

export const deviceSchema = z
  .looseObject({
    name: z.string().min(1),
    /** What kind of thing it is, like 手机 or 电脑; the use page gives each its own lane. */
    category: z.string().min(1),
    bought: z.looseObject({ on: dateKey, price }),
    sold: z.looseObject({ on: dateKey, price }).optional(),
    /** Still owned but no longer used. */
    idle: z.boolean().default(false),
    note: z.string().optional(),
  })
  .refine((device) => !device.sold || device.bought.on <= device.sold.on, 'Sold before bought.')

export const devicesSchema = z.record(id, deviceSchema)

export const manifestSchema = z.looseObject({
  schemaVersion: z.literal(SCHEMA_VERSION),
  generator: z.string().min(1),
  updatedAt: timestamp,
})

export type MomentRecord = z.output<typeof momentRecordSchema>
export type MomentRecordInput = z.input<typeof momentRecordSchema>
export type MomentMedia = MomentRecord['media'][number]
export type MomentTopic = z.output<typeof momentTopicSchema>
export type MomentFood = z.output<typeof momentFoodSchema>
export type FoodVerdict = z.output<typeof foodVerdictSchema>
export type PublishedImage = z.output<typeof publishedImageSchema>
export type PublishedVideo = z.output<typeof publishedVideoSchema>
export type Place = z.output<typeof placeSchema>
export type PlaceKind = z.output<typeof placeKindSchema>
export type Places = z.output<typeof placesSchema>
export type TripRecord = z.output<typeof tripRecordSchema>
export type TripRecordInput = z.input<typeof tripRecordSchema>
export type Route = z.output<typeof routeSchema>
export type Routes = z.output<typeof routesSchema>
export type Device = z.output<typeof deviceSchema>
export type Devices = z.output<typeof devicesSchema>
export type Manifest = z.output<typeof manifestSchema>

/** The directory a Moment's file sits in under `data/moments`: its year and month. */
export function getMomentDirectory(occurredAt: string) {
  return `${occurredAt.slice(0, 4)}/${occurredAt.slice(5, 7)}`
}

/** A Moment's file under `data/`, like `moments/2026/10/01K7….json`. */
export function getMomentFile(moment: Pick<MomentRecord, 'occurredAt' | 'uid'>) {
  return `moments/${getMomentDirectory(moment.occurredAt)}/${moment.uid}.json`
}
