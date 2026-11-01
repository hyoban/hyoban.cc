import { z } from 'zod'

// The values every record is made of, defined once for the Moments, the entries and the car.
//
// Units, wherever a field doesn't name its own: distances in metres, energy in watt-hours,
// durations in seconds, and money in yuan (CNY) unless a `currency` sits beside it. Times
// are ISO timestamps with an explicit offset; what happened carries the offset it happened
// at, while `createdAt` and `updatedAt`, when a record was written, are written in UTC (`Z`).

/** A kebab-case id: a place, a trip, a device, a music track or a Moment's slug. */
export const ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
export const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/
export const DATE_KEY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
export const TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})$/

export const id = z.string().regex(ID_PATTERN, 'Use a kebab-case id.')
export const ulid = z.string().regex(ULID_PATTERN, 'Use a ULID.')
export const dateKey = z.string().regex(DATE_KEY_PATTERN, 'Use a YYYY-MM-DD date.')
export const timestamp = z
  .string()
  .regex(TIMESTAMP_PATTERN, 'Use an ISO timestamp with an explicit offset.')
  .refine((value) => !Number.isNaN(Date.parse(value)), 'Use a real date and time.')
/** When something happened: a timestamp with its offset, or just the day. */
export const occurredAt = z.union([dateKey, timestamp])
export const count = z.int().positive()
/** An amount of money, in yuan unless a `currency` beside it says otherwise. */
export const price = z.number().nonnegative()
/** An ISO 4217 code, like `CNY` or `USD`. */
export const currency = z.string().regex(/^[A-Z]{3}$/)

export const unique = (values: readonly string[]) => new Set(values).size === values.length

/** How something eaten turned out: worth having again, passable, or not. */
export const foodVerdictSchema = z.enum(['good', 'ok', 'bad'])

export type FoodVerdict = z.output<typeof foodVerdictSchema>

/** Now as `createdAt` and `updatedAt` are written: UTC to the second, like `2026-11-01T05:28:14Z`. */
export function getRecordTime(date = new Date()) {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z')
}
