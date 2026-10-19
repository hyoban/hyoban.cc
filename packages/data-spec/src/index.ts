import { z } from 'zod'

// The data spec: the format the recording app stores, the repository commits under
// `data/` and the site reads. Every object is loose, so a field one side doesn't know
// yet travels through the other untouched; `pnpm data:schema` writes these schemas out
// as JSON Schema for the app. Changes only add optional fields; a change that doesn't
// bumps SCHEMA_VERSION.

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
})

/** An uploaded video. */
export const publishedVideoSchema = r2Object.extend({
  contentType: z.string().startsWith('video/'),
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

export const momentRecordSchema = z
  .looseObject({
    uid: ulid,
    occurredAt,
    /** Place ids in visiting order. */
    places: z.array(id).refine(unique, 'Duplicate place id.').default([]),
    /** The words as written, with their line breaks. */
    text: z.string(),
    hidden: z.boolean().default(false),
    media: z
      .array(momentMediaSchema)
      .refine((media) => unique(media.map((item) => item.uid)), 'Duplicate media uid.')
      .default([]),
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

export const placeSchema = z.looseObject({
  name: z.string().min(1),
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
  /** A note to self; the site doesn't show it. */
  note: z.string().optional(),
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

/** A price in yuan, as paid or got back. */
const price = z.number().nonnegative()

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
export type PublishedImage = z.output<typeof publishedImageSchema>
export type PublishedVideo = z.output<typeof publishedVideoSchema>
export type Place = z.output<typeof placeSchema>
export type Places = z.output<typeof placesSchema>
export type TripRecord = z.output<typeof tripRecordSchema>
export type TripRecordInput = z.input<typeof tripRecordSchema>
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
