import { z } from 'zod'

import { id, timestamp } from './primitives.ts'

export * from './vehicle.ts'
export * from './media.ts'
export * from './legacy.ts'
export { ID_PATTERN, foodVerdictSchema, getRecordTime, type FoodVerdict } from './primitives.ts'

// The data spec: the format the recording app stores, the repository commits under
// `data/` and the site reads. Every object is loose, so a field one side doesn't know
// yet travels through the other untouched; `pnpm data:schema` writes these schemas out
// as JSON Schema for the app. Changes only add optional fields, or tighten a rule every
// stored record already keeps (a Moment at one place at most); any other change bumps
// SCHEMA_VERSION. The values and units every record shares are in `./primitives.ts`; the
// entries and collections in `./entry.ts`, the first shape in `./legacy.ts`.

// 3: Moments became entries with parts, trips collections (`./entry.ts`); a manifest of 2 is the
// first shape, still read while the data moves over.
export const SCHEMA_VERSION = 3

const point = {
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
}

/**
 * What sort of place it is, by group: a city, town or area too wide for one point; then
 * somewhere to eat, shop, stay, see, have fun or get about, and the places of everyday life.
 * Each group's first kind is the group as a whole, for a place no finer kind fits; the
 * finer ones say what it is, a café or a metro station, as the map services do.
 */
export const placeKindGroups = {
  area: ['city', 'town', 'region'],
  food: ['food', 'cafe', 'tea', 'bar', 'dessert'],
  shopping: ['shopping', 'supermarket', 'market'],
  stay: ['stay', 'camp'],
  scenic: ['scenic', 'park', 'museum', 'temple', 'nature'],
  leisure: ['leisure', 'show', 'sport'],
  transport: [
    'transport',
    'station',
    'metro',
    'bus',
    'airport',
    'port',
    'parking',
    'charging',
    'fuel',
  ],
  life: ['life', 'hospital', 'school', 'work', 'residential'],
} as const

export type PlaceKindGroup = keyof typeof placeKindGroups

export const placeKindSchema = z.enum(
  Object.values(placeKindGroups).flat() as [PlaceKindValue, ...PlaceKindValue[]],
)

type PlaceKindValue = (typeof placeKindGroups)[PlaceKindGroup][number]

/** The group a kind is in, `cafe` food. */
export function getPlaceKindGroup(kind: PlaceKindValue): PlaceKindGroup {
  return (Object.keys(placeKindGroups) as PlaceKindGroup[]).find((group) =>
    (placeKindGroups[group] as readonly string[]).includes(kind),
  )!
}

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
  /**
   * The venue in another app or map service it came from, by that one's id: Swarm's venue, or
   * the place AMap or Tencent knows. It is that place and no other, so publishing never takes it
   * for one nearby or of the same name, and the same venue picked again is this place.
   */
  source: z
    .looseObject({ app: z.enum(['swarm', 'amap', 'tencent']), venue: z.string().min(1) })
    .optional(),
  createdAt: timestamp.optional(),
})

export const placesSchema = z.record(id, placeSchema)

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

export const manifestSchema = z.looseObject({
  schemaVersion: z.union([z.literal(2), z.literal(SCHEMA_VERSION)]),
  generator: z.string().min(1),
  updatedAt: timestamp,
})

export type Place = z.output<typeof placeSchema>
export type PlaceKind = z.output<typeof placeKindSchema>
export type Places = z.output<typeof placesSchema>
export type Route = z.output<typeof routeSchema>
export type Routes = z.output<typeof routesSchema>
export type Manifest = z.output<typeof manifestSchema>
