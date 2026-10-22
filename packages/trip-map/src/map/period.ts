// A year, or a month of one, the map is narrowed to: only the places with Moments then
// and the trips under way then stay on it, and the Moments list beside it shows them.

export type Period = Readonly<{ month?: number | undefined; year: number }>

/** `2025` for a year, `2025-06` for a month: what the period's date keys start with. */
export function getPeriodKey(period: Period) {
  return period.month === undefined
    ? String(period.year)
    : `${period.year}-${String(period.month).padStart(2, '0')}`
}

export function isSamePeriod(first: Period | undefined, second: Period | undefined) {
  return first?.year === second?.year && first?.month === second?.month
}

/** Whether a date key (`2025-06-12`) or month key (`2025-06`) falls in the period. */
export function isInPeriod(key: string, period: Period) {
  return key.startsWith(getPeriodKey(period))
}

/** Whether a stretch of days, from `start` to `end` (date keys), runs into the period. */
export function overlapsPeriod(start: string, end: string, period: Period) {
  const key = getPeriodKey(period)

  return start.slice(0, key.length) <= key && end.slice(0, key.length) >= key
}
