import { z } from 'zod'

import {
  ID_PATTERN,
  momentMediaSchema,
  momentTopicSchema,
  foodVerdictSchema,
  type MomentRecord,
  type TripRecord,
} from './index.ts'

// The second shape of the data: every record is a moment and an optional point (an Entry),
// whatever it holds is a part a plugin defines (words, photos, a meal, a bill, a charge), a
// trip is a Collection that takes the entries in its span, and the footprints are tracks,
// some spans of which can be made public. Each of these is public or private, inherited
// from what holds it. Unknown parts and fields travel through untouched, like the rest of
// the spec; a new plugin is a new optional part and needs no new SCHEMA_VERSION.

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
const occurredAt = z.union([dateKey, timestamp])
const count = z.int().positive()
const price = z.number().nonnegative()

/**
 * Who sees something. Only `public` and `private` for now; it is to widen to `followers`, a
 * `{ circle }` or `{ people }` when homes connect, with the same inheritance.
 */
export const audienceSchema = z.enum(['public', 'private'])
export type Audience = z.output<typeof audienceSchema>

/** Where something happened, and how that is known. */
export const entryPointSchema = z.looseObject({
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  /** Metres. */
  accuracy: z.number().nonnegative().optional(),
  from: z.enum(['photo', 'track', 'device', 'manual', 'place']),
})

/** What every part carries: its plugin's version, and who sees it when not its entry's say. */
const partBase = {
  v: count,
  visibility: audienceSchema.optional(),
}

export const textPartSchema = z.looseObject({
  ...partBase,
  /** The words as written, with their line breaks; never changed. */
  text: z.string(),
})

export const mediaPartSchema = z.looseObject({
  ...partBase,
  items: z
    .array(
      // Each photo has its own point, as a day's photos are taken in several places.
      z.intersection(momentMediaSchema, z.looseObject({ point: entryPointSchema.optional() })),
    )
    .refine(
      (items) => new Set(items.map((item) => item.uid)).size === items.length,
      'Duplicate media uid.',
    ),
})

/** A meal: its own cost and people, so a private bill still leaves what the meal cost public. */
export const foodPartSchema = z.looseObject({
  ...partBase,
  dishes: z.array(z.string().min(1)).default([]),
  verdict: foodVerdictSchema.optional(),
  cost: price.optional(),
  people: count.optional(),
})

/** A bill from the ledger. Only `amount`, `currency`, `category` and `note` are ever public. */
export const billPartSchema = z.looseObject({
  ...partBase,
  kind: z.enum(['expense', 'income', 'transfer']).default('expense'),
  /** In the currency's unit, as `12.5`. */
  amount: price,
  currency: z.string().regex(/^[A-Z]{3}$/),
  /** The category as shown, `餐饮 › 零食`. */
  category: z.string().optional(),
  note: z.string().optional(),
  /** The ledger's own ids, never published. */
  book: z.string().optional(),
  account: z.string().optional(),
  ledgerId: z.string().optional(),
  refund: z.boolean().optional(),
  reimburse: z.string().optional(),
})

/** A charge of the car. */
export const chargePartSchema = z.looseObject({
  ...partBase,
  type: z.enum(['AC', 'DC']).optional(),
  energyWh: z.number().nonnegative().optional(),
  cost: price.optional(),
  durationMin: z.number().nonnegative().optional(),
  station: z.string().optional(),
  socFrom: z.number().min(0).max(100).optional(),
  socTo: z.number().min(0).max(100).optional(),
})

/** The parts by plugin; one this version doesn't know is kept as it is. */
export const entryPartsSchema = z.looseObject({
  text: textPartSchema.optional(),
  media: mediaPartSchema.optional(),
  food: foodPartSchema.optional(),
  bill: billPartSchema.optional(),
  charge: chargePartSchema.optional(),
})

export const entryLinkSchema = z.looseObject({ rel: z.string().min(1), to: ulid })

export const entrySchema = z.looseObject({
  uid: ulid,
  /** When: a timestamp with its offset, or just the day. */
  at: occurredAt,
  /** When something that lasted ended: a charge, a stay. */
  end: timestamp.optional(),
  point: entryPointSchema.optional(),
  /** The named place it was at. */
  place: id.optional(),
  parts: entryPartsSchema.default({}),
  links: z.array(entryLinkSchema).optional(),
  visibility: audienceSchema.optional(),
  /**
   * What it is mostly about, where the parts don't say: tech, photo and life. Travel, food
   * and car are kept for the records that were given them.
   */
  topic: momentTopicSchema.optional(),
  /** Where it sits among its day's entries when their times don't say, lower first. */
  order: count.optional(),
  published: z.looseObject({ at: timestamp }).optional(),
  createdAt: timestamp,
  updatedAt: timestamp,
})

export type Entry = z.output<typeof entrySchema>
export type EntryInput = z.input<typeof entrySchema>
export type EntryParts = Entry['parts']
export type EntryPoint = z.output<typeof entryPointSchema>

/** The route a trip falls back on where it has no public track: legs not driven, and back the same way. */
const routeHintsSchema = z.looseObject({
  straightLegs: z
    .array(z.looseObject({ from: id, to: id, by: z.enum(['ferry', 'plane', 'train']) }))
    .default([]),
  sameWayBack: z.array(z.looseObject({ from: id, to: id })).default([]),
})

export const collectionSchema = z
  .looseObject({
    uid: ulid,
    /** The name in its address, `2026-yunnan`: a trip's old id. */
    slug: id.optional(),
    kind: z.literal('trip'),
    title: z.string().min(1),
    span: z.looseObject({ start: occurredAt, end: occurredAt }),
    /** Entries outside the span that belong to it, and inside it that don't. */
    include: z.array(ulid).default([]),
    exclude: z.array(ulid).default([]),
    /** Photos for its cover, best first. */
    covers: z.array(z.looseObject({ entry: ulid, media: ulid })).default([]),
    visibility: audienceSchema.optional(),
    /** Facets set by hand; the rest are worked out. */
    facets: z.record(z.string(), z.unknown()).optional(),
    route: routeHintsSchema.optional(),
    /** A track from src/data/music.ts. */
    music: id.optional(),
    note: z.string().optional(),
    createdAt: timestamp,
    updatedAt: timestamp,
  })
  .refine(
    (collection) => collection.span.start.slice(0, 10) <= collection.span.end.slice(0, 10),
    'The collection ends before it starts.',
  )

export type Collection = z.output<typeof collectionSchema>
export type CollectionInput = z.input<typeof collectionSchema>

/** A day of footprints, on the phone alone: `[t (ms), lng, lat, accuracy, speed?]` samples. */
export const trackSchema = z.looseObject({
  day: dateKey,
  segments: z.array(
    z.looseObject({
      mode: z.enum(['walk', 'ride', 'drive', 'train', 'flight']).optional(),
      samples: z.array(z.array(z.number()).min(4).max(5)),
    }),
  ),
})

/** A span of the footprints made public, by hand or with a trip it follows. */
export const trackShareSchema = z.looseObject({
  uid: ulid,
  start: timestamp,
  end: timestamp,
  collection: ulid.optional(),
  /** Metres the public line is simplified to. */
  simplify: z.number().positive().default(30),
})

/** What a public span publishes: its line simplified, `[lng, lat, unix seconds]`, a segment per unbroken stretch. */
export const publicTrackSchema = trackShareSchema.extend({
  segments: z
    .array(
      z.looseObject({
        mode: z.enum(['walk', 'ride', 'drive', 'train', 'flight']).optional(),
        coordinates: z.array(z.array(z.number()).min(2).max(3)).min(2),
      }),
    )
    .default([]),
  /** Metres along it all. */
  distance: z.number().nonnegative().optional(),
  updatedAt: timestamp.optional(),
})

export type Track = z.output<typeof trackSchema>
export type TrackShare = z.output<typeof trackShareSchema>
export type PublicTrack = z.output<typeof publicTrackSchema>

// Plugins: the data half (its part, default audience, public fields, search text and facets)
// shared by the site, the API and the app; each client registers its own UI for them.

export type PluginDefault = 'inherit' | Audience

export interface Plugin<Part = Record<string, unknown>> {
  id: string
  version: number
  /** `inherit` follows the entry. */
  defaultVisibility: PluginDefault
  /** The only fields a public part keeps, besides `v`; all of them when left out. */
  publicFields?: readonly string[]
  /** A private part of it stays on the phone rather than going to the API. */
  staysOnDevice?: boolean
  /** Words to find it by. */
  searchText?: (part: Part) => string
  /** Plain words for a reader that doesn't know the plugin. */
  fallback?: (part: Part) => string
}

export function definePlugin<Part>(plugin: Plugin<Part>) {
  return plugin
}

type Parts = {
  [K in 'text' | 'media' | 'food' | 'bill' | 'charge']: NonNullable<
    z.output<typeof entryPartsSchema>[K]
  >
}

export const plugins = {
  text: definePlugin<Parts['text']>({
    id: 'text',
    version: 1,
    defaultVisibility: 'inherit',
    searchText: (part) => part.text,
    fallback: (part) => part.text,
  }),
  media: definePlugin<Parts['media']>({
    id: 'media',
    version: 1,
    defaultVisibility: 'inherit',
    searchText: (part) => part.items.map((item) => item.alt).join(' '),
    fallback: (part) => `${part.items.length} 张照片`,
  }),
  food: definePlugin<Parts['food']>({
    id: 'food',
    version: 1,
    defaultVisibility: 'inherit',
    searchText: (part) => part.dishes.join(' '),
    fallback: (part) => part.dishes.join('、'),
  }),
  bill: definePlugin<Parts['bill']>({
    id: 'bill',
    version: 1,
    defaultVisibility: 'private',
    publicFields: ['kind', 'amount', 'currency', 'category', 'note'],
    staysOnDevice: true,
    searchText: (part) => [part.category, part.note].filter(Boolean).join(' '),
    fallback: (part) => `${part.category ?? ''} ${part.amount} ${part.currency}`.trim(),
  }),
  charge: definePlugin<Parts['charge']>({
    id: 'charge',
    version: 1,
    defaultVisibility: 'inherit',
    publicFields: ['type', 'energyWh', 'cost', 'durationMin'],
    searchText: (part) => part.station ?? '',
    fallback: (part) =>
      `充电 ${part.energyWh === undefined ? '' : `${part.energyWh / 1000} kWh`}`.trim(),
  }),
} satisfies Record<string, Plugin<never>>

export type PluginId = keyof typeof plugins

function getPlugin(id: string): Plugin<never> | undefined {
  return (plugins as Record<string, Plugin<never>>)[id]
}

// Who sees what: its own say, else what holds it, else its plugin's default, else private.

/** An entry's audience: its own, else private for tech, else public. */
export function getEntryAudience(entry: Pick<Entry, 'visibility' | 'topic'>): Audience {
  return entry.visibility ?? (entry.topic === 'tech' ? 'private' : 'public')
}

/**
 * A part's audience: its own, else its container's (a bill's book, on the phone), else its
 * plugin's default, following the entry for `inherit` and an unknown plugin.
 */
export function getPartAudience(
  entry: Pick<Entry, 'visibility' | 'topic'>,
  pluginId: string,
  part: { visibility?: Audience } | undefined,
  container?: Audience,
): Audience {
  if (getEntryAudience(entry) === 'private') {
    return 'private'
  }

  const fallback = getPlugin(pluginId)?.defaultVisibility ?? 'inherit'

  return (
    part?.visibility ??
    container ??
    (fallback === 'inherit' ? getEntryAudience(entry) : fallback) ??
    'private'
  )
}

/**
 * What of an entry the public sees: private parts gone and public ones down to their public
 * fields; undefined when the entry is private, or every part it had is.
 */
export function getPublicEntry<T extends Entry>(entry: T): T | undefined {
  if (getEntryAudience(entry) === 'private') {
    return undefined
  }

  const parts: Record<string, unknown> = {}
  let had = 0

  for (const [pluginId, part] of Object.entries(entry.parts)) {
    if (part === undefined) {
      continue
    }

    had += 1

    if (getPartAudience(entry, pluginId, part as { visibility?: Audience }) !== 'public') {
      continue
    }

    const fields = getPlugin(pluginId)?.publicFields
    parts[pluginId] = fields
      ? Object.fromEntries(
          Object.entries(part as Record<string, unknown>).filter(
            ([key]) => key === 'v' || key === 'visibility' || fields.includes(key),
          ),
        )
      : part
  }

  if (had > 0 && Object.keys(parts).length === 0) {
    return undefined
  }

  return { ...entry, parts }
}

/** What of an entry leaves the phone: the private parts of plugins that stay on it taken out. */
export function getUploadEntry<T extends Entry>(
  entry: T,
  containers: Record<string, Audience> = {},
): T {
  const parts = Object.fromEntries(
    Object.entries(entry.parts).filter(([pluginId, part]) => {
      if (!getPlugin(pluginId)?.staysOnDevice) {
        return true
      }

      return (
        getPartAudience(
          entry,
          pluginId,
          part as { visibility?: Audience },
          containers[pluginId],
        ) === 'public'
      )
    }),
  )

  return { ...entry, parts }
}

/** The words an entry is found by, from every part's plugin. */
export function getEntrySearchText(entry: Entry) {
  return Object.entries(entry.parts)
    .map(([pluginId, part]) => (part ? getPlugin(pluginId)?.searchText?.(part as never) : ''))
    .filter(Boolean)
    .join('\n')
}

/** An entry with nothing but its moment and point: a place passed, a stop confirmed. */
export function isMarkEntry(entry: Pick<Entry, 'parts'>) {
  return Object.values(entry.parts).every((part) => part === undefined)
}

/** Whether a collection holds an entry: in its span, or included, and not excluded. */
export function isInCollection(collection: Collection, entry: Pick<Entry, 'uid' | 'at'>) {
  if (collection.exclude.includes(entry.uid)) {
    return false
  }

  if (collection.include.includes(entry.uid)) {
    return true
  }

  return isWithin(entry.at, collection.span.start, collection.span.end)
}

/** Whether a moment falls in a span whose ends are timestamps or whole days. */
export function isWithin(at: string, start: string, end: string) {
  const day = at.slice(0, 10)

  if (start.length === 10 || at.length === 10) {
    if (day < start.slice(0, 10)) return false
  } else if (Date.parse(at) < Date.parse(start)) {
    return false
  }

  if (end.length === 10 || at.length === 10) {
    return day <= end.slice(0, 10)
  }

  return Date.parse(at) <= Date.parse(end)
}

// Facets: what a collection adds up to, from its members' parts. Only what the reader can
// see goes in: the app passes everything, the site the public entries alone.

export interface CollectionFacets {
  spending?: { amount: number; currency: string; bills: number }
  charging?: { sessions: number; energyWh: number; cost: number }
  food?: { meals: number; good: number }
  places?: { count: number }
  distance?: { metres: number }
}

export function getCollectionFacets(
  members: readonly Entry[],
  tracks: readonly Pick<PublicTrack, 'distance'>[] = [],
): CollectionFacets {
  const facets: CollectionFacets = {}
  const bills = members.flatMap((entry) => (entry.parts.bill ? [entry.parts.bill] : []))
  const expenses = bills.filter((bill) => bill.kind === 'expense')

  if (expenses.length > 0) {
    facets.spending = {
      amount: round(expenses.reduce((sum, bill) => sum + bill.amount, 0)),
      currency: expenses[0]!.currency,
      bills: expenses.length,
    }
  }

  const charges = members.flatMap((entry) => (entry.parts.charge ? [entry.parts.charge] : []))

  if (charges.length > 0) {
    facets.charging = {
      sessions: charges.length,
      energyWh: charges.reduce((sum, charge) => sum + (charge.energyWh ?? 0), 0),
      cost: round(charges.reduce((sum, charge) => sum + (charge.cost ?? 0), 0)),
    }
  }

  const meals = members.flatMap((entry) => (entry.parts.food ? [entry.parts.food] : []))

  if (meals.length > 0) {
    facets.food = {
      meals: meals.length,
      good: meals.filter((meal) => meal.verdict === 'good').length,
    }
  }

  const places = new Set(members.flatMap((entry) => (entry.place ? [entry.place] : [])))

  if (places.size > 0) {
    facets.places = { count: places.size }
  }

  const metres = tracks.reduce((sum, track) => sum + (track.distance ?? 0), 0)

  if (metres > 0) {
    facets.distance = { metres: Math.round(metres) }
  }

  return facets
}

function round(value: number) {
  return Math.round(value * 100) / 100
}

// From the first shape and back. A Moment is an entry of words, photos and a meal; a trip a
// collection, its old id kept as the slug and its waypoints marks of their own. Both ways
// are exact, so `data/` can move over and back again without losing a field.

const MOMENT_KEYS = new Set([
  'uid',
  'occurredAt',
  'places',
  'text',
  'hidden',
  'topic',
  'media',
  'food',
  'trip',
  'order',
  'published',
  'createdAt',
  'updatedAt',
])

/** A Moment as an entry. A hidden one is private; a tech one is private by its topic. */
export function momentToEntry(moment: MomentRecord): Entry {
  const rest = Object.fromEntries(Object.entries(moment).filter(([key]) => !MOMENT_KEYS.has(key)))
  const parts: Record<string, unknown> = { text: { v: 1, text: moment.text } }

  if (moment.media.length > 0) {
    parts.media = { v: 1, items: moment.media }
  }

  if (moment.food) {
    const { bill, ...food } = moment.food
    parts.food = {
      v: 1,
      ...food,
      ...(bill ? { cost: bill.amount } : {}),
      ...(bill?.people ? { people: bill.people } : {}),
    }
  }

  return {
    ...rest,
    uid: moment.uid,
    at: moment.occurredAt,
    ...(moment.places[0] ? { place: moment.places[0] } : {}),
    parts: parts as EntryParts,
    ...(moment.hidden ? { visibility: 'private' as const } : {}),
    ...(moment.topic ? { topic: moment.topic } : {}),
    ...(moment.order ? { order: moment.order } : {}),
    ...(moment.published ? { published: moment.published } : {}),
    createdAt: moment.createdAt,
    updatedAt: moment.updatedAt,
  }
}

const ENTRY_KEYS = new Set([
  'uid',
  'at',
  'end',
  'point',
  'place',
  'parts',
  'links',
  'visibility',
  'topic',
  'order',
  'published',
  'createdAt',
  'updatedAt',
])

/**
 * An entry as a Moment, for what still reads the first shape: its words, photos and meal;
 * other parts, and those private by their own say, are left out. Hidden when private by its own say.
 */
export function entryToMoment(entry: Entry, trip?: string): MomentRecord {
  const rest = Object.fromEntries(Object.entries(entry).filter(([key]) => !ENTRY_KEYS.has(key)))
  // A part made private by its own say isn't the first shape's to show.
  const food = entry.parts.food?.visibility === 'private' ? undefined : entry.parts.food
  const media = entry.parts.media?.visibility === 'private' ? undefined : entry.parts.media

  return {
    ...rest,
    uid: entry.uid,
    occurredAt: entry.at,
    places: entry.place ? [entry.place] : [],
    text: entry.parts.text?.text ?? '',
    hidden: entry.visibility === 'private',
    ...(entry.topic ? { topic: entry.topic } : {}),
    media: (media?.items ?? []).map(({ point: _point, ...item }) => item) as MomentRecord['media'],
    ...(food
      ? {
          food: {
            ...Object.fromEntries(
              Object.entries(food).filter(
                ([key]) => !['v', 'visibility', 'cost', 'people'].includes(key),
              ),
            ),
            dishes: food.dishes,
            ...(food.cost === undefined
              ? {}
              : { bill: { amount: food.cost, ...(food.people ? { people: food.people } : {}) } }),
          } as MomentRecord['food'],
        }
      : {}),
    ...(trip ? { trip } : {}),
    ...(entry.order ? { order: entry.order } : {}),
    ...(entry.published ? { published: entry.published } : {}),
    createdAt: entry.createdAt,
    updatedAt: entry.updatedAt,
  }
}

/**
 * A Moment written in the first shape, over the entry it was: its words, photos, meal, time,
 * place and whether it is hidden replace the entry's, and everything the first shape doesn't
 * hold (other parts, points, links) stays. Each photo keeps the point the entry had for it.
 */
export function mergeMomentIntoEntry(prior: Entry | undefined, moment: MomentRecord): Entry {
  const next = momentToEntry(moment)

  if (!prior) {
    return next
  }

  const points = new Map((prior.parts.media?.items ?? []).map((item) => [item.uid, item.point]))
  const parts: Record<string, unknown> = {
    ...prior.parts,
    text: { ...prior.parts.text, ...next.parts.text },
  }

  for (const plugin of ['media', 'food'] as const) {
    if (next.parts[plugin]) {
      parts[plugin] = { ...prior.parts[plugin], ...next.parts[plugin] }
    } else {
      delete parts[plugin]
    }
  }

  if (next.parts.media) {
    parts.media = {
      ...(parts.media as object),
      items: next.parts.media.items.map((item) =>
        points.get(item.uid) ? { ...item, point: points.get(item.uid) } : item,
      ),
    }
  }

  const { visibility: _visibility, place: _place, topic: _topic, order: _order, ...kept } = prior

  return {
    ...kept,
    ...next,
    ...(prior.point ? { point: prior.point } : {}),
    ...(prior.links ? { links: prior.links } : {}),
    ...(prior.end ? { end: prior.end } : {}),
    // Hidden or not is the Moment's say; one made public against its topic stays public.
    ...(!next.visibility && prior.visibility === 'public' ? { visibility: 'public' as const } : {}),
    parts: parts as EntryParts,
  }
}

/** Whether an entry is a Moment in the first shape: it has words. */
export function isMomentEntry(entry: Pick<Entry, 'parts'>) {
  return entry.parts.text !== undefined
}

const TRIP_KEYS = new Set([
  'id',
  'title',
  'start',
  'end',
  'music',
  'note',
  'waypoints',
  'straightLegs',
  'sameWayBack',
  'covers',
  'createdAt',
  'updatedAt',
])

/**
 * A trip as a collection, with its waypoints as marks: a waypoint that followed the one before
 * takes that one's day alone. `moments` are those naming it, or naming another trip in its span.
 */
export function tripToCollection(
  trip: TripRecord,
  moments: readonly Pick<MomentRecord, 'uid' | 'occurredAt' | 'trip'>[] = [],
): { collection: Collection; marks: Entry[] } {
  const rest = Object.fromEntries(Object.entries(trip).filter(([key]) => !TRIP_KEYS.has(key)))
  const uid = getStableUlid(trip.createdAt, `trip:${trip.id}`)
  const inSpan = (at: string) => isWithin(at, trip.start, trip.end)
  let previous: string | undefined
  const marks = trip.waypoints.map((waypoint, index): Entry => {
    const at = waypoint.at ?? previous!.slice(0, 10)
    previous = waypoint.at ?? previous

    return {
      uid: getStableUlid(waypoint.at ?? trip.createdAt, `waypoint:${trip.id}:${index}`),
      at,
      place: waypoint.place,
      parts: {},
      order: index + 1,
      // A trip was public as soon as it was written, its waypoints with it.
      published: { at: trip.updatedAt },
      createdAt: trip.createdAt,
      updatedAt: trip.updatedAt,
    }
  })

  return {
    collection: {
      ...rest,
      uid,
      slug: trip.id,
      kind: 'trip',
      title: trip.title,
      span: { start: trip.start, end: trip.end },
      include: moments
        .filter((moment) => moment.trip === trip.id && !inSpan(moment.occurredAt))
        .map((moment) => moment.uid),
      exclude: moments
        .filter((moment) => moment.trip && moment.trip !== trip.id && inSpan(moment.occurredAt))
        .map((moment) => moment.uid),
      covers: trip.covers.map((cover) => ({ entry: cover.moment, media: cover.media })),
      ...(trip.straightLegs.length > 0 || trip.sameWayBack.length > 0
        ? { route: { straightLegs: trip.straightLegs, sameWayBack: trip.sameWayBack } }
        : {}),
      ...(trip.music ? { music: trip.music } : {}),
      ...(trip.note === undefined ? {} : { note: trip.note }),
      createdAt: trip.createdAt,
      updatedAt: trip.updatedAt,
    },
    marks,
  }
}

const COLLECTION_KEYS = new Set([
  'uid',
  'slug',
  'kind',
  'title',
  'span',
  'include',
  'exclude',
  'covers',
  'visibility',
  'facets',
  'route',
  'music',
  'note',
  'createdAt',
  'updatedAt',
])

/**
 * A collection as a trip, for what still reads the first shape: its marks with a place, in its
 * span, become the waypoints, one on the day of the waypoint before it following that one.
 */
export function collectionToTrip(collection: Collection, entries: readonly Entry[]): TripRecord {
  const rest = Object.fromEntries(
    Object.entries(collection).filter(([key]) => !COLLECTION_KEYS.has(key)),
  )
  const marks = entries
    .filter((entry) => isMarkEntry(entry) && entry.place && isInCollection(collection, entry))
    .toSorted((a, b) => (a.order ?? 0) - (b.order ?? 0) || compareAt(a.at, b.at))
  let previous: string | undefined
  const waypoints = marks.map((mark) => {
    const follows =
      mark.at.length === 10 && previous !== undefined && previous.slice(0, 10) === mark.at
    previous = follows ? previous : mark.at

    return follows ? { place: mark.place! } : { at: mark.at, place: mark.place! }
  })

  return {
    ...rest,
    id: collection.slug ?? collection.uid.toLowerCase(),
    title: collection.title,
    start: collection.span.start.slice(0, 10),
    end: collection.span.end.slice(0, 10),
    ...(collection.music ? { music: collection.music } : {}),
    ...(collection.note === undefined ? {} : { note: collection.note }),
    waypoints,
    straightLegs: collection.route?.straightLegs ?? [],
    sameWayBack: collection.route?.sameWayBack ?? [],
    covers: collection.covers.map((cover) => ({ moment: cover.entry, media: cover.media })),
    createdAt: collection.createdAt,
    updatedAt: collection.updatedAt,
  }
}

function compareAt(a: string, b: string) {
  return a < b ? -1 : a > b ? 1 : 0
}

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/**
 * A ULID that is the same each time for the same time and seed, so a migration run twice
 * names things the same: the time's milliseconds, then 80 bits of the seed's FNV-1a hashes.
 */
export function getStableUlid(time: string, seed: string) {
  let milliseconds = Date.parse(time.length === 10 ? `${time}T00:00:00Z` : time)
  let head = ''

  for (let index = 0; index < 10; index += 1) {
    head = CROCKFORD[milliseconds % 32]! + head
    milliseconds = Math.floor(milliseconds / 32)
  }

  let tail = ''

  for (let round = 0; tail.length < 16; round += 1) {
    let hash = 0x811c9dc5

    for (const character of `${round}:${seed}`) {
      hash ^= character.codePointAt(0)!
      hash = Math.imul(hash, 0x01000193) >>> 0
    }

    for (let index = 0; index < 6 && tail.length < 16; index += 1) {
      tail += CROCKFORD[hash & 31]
      hash >>>= 5
    }
  }

  return head + tail
}

// Where each lives under `data/`: an entry by its month like a Moment, a collection by its
// slug (or uid), a public track by its uid.

/** An entry's file under `data/`, like `entries/2026/10/01K7….json`. */
export function getEntryFile(entry: Pick<Entry, 'at' | 'uid'>) {
  return `entries/${entry.at.slice(0, 4)}/${entry.at.slice(5, 7)}/${entry.uid}.json`
}

/** A collection's file under `data/`, like `collections/2026-yunnan.json`. */
export function getCollectionFile(collection: Pick<Collection, 'slug' | 'uid'>) {
  return `collections/${getCollectionKey(collection)}.json`
}

/** What a collection is known by in addresses and the database: its slug, else its uid in lowercase. */
export function getCollectionKey(collection: Pick<Collection, 'slug' | 'uid'>) {
  return collection.slug ?? collection.uid.toLowerCase()
}

/** A public track's file under `data/`. */
export function getPublicTrackFile(track: Pick<PublicTrack, 'uid'>) {
  return `tracks/${track.uid}.json`
}
