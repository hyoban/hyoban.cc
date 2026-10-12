// Dates on the cards. A date is a `YYYY-MM-DD` key; the calendar day it names is
// formatted in the messages' locale, in UTC so it never slips a day.

export type DatePart = { type: 'day' | 'literal' | 'month' | 'other' | 'weekday'; value: string }

export function parseDateKey(dateKey: string) {
  return new Date(`${dateKey}T00:00:00Z`)
}

/** `10月1日`, or with `weekday` on, `10月1日周四`. */
export function formatDate(dateKey: string, locale: string, options: { weekday?: boolean } = {}) {
  return formatter(locale, options.weekday).format(parseDateKey(dateKey))
}

/** `9月12日 – 9月16日`. */
export function formatDateRange(start: string, end: string, locale: string) {
  return `${formatDate(start, locale)} – ${formatDate(end, locale)}`
}

/** The date in pieces, so its numbers can be shown rolling while the rest stays text. */
export function getDateParts(dateKey: string, locale: string): DatePart[] {
  return formatter(locale, true)
    .formatToParts(parseDateKey(dateKey))
    .map(({ type, value }) => ({
      type:
        type === 'day' || type === 'month' || type === 'weekday' || type === 'literal'
          ? type
          : 'other',
      value,
    }))
}

function formatter(locale: string, weekday: boolean | undefined) {
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: 'long',
    timeZone: 'UTC',
    ...(weekday ? { weekday: 'short' as const } : {}),
  })
}
