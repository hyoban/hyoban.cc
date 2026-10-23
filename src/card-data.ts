import type { Moment } from '@/moments'
import type { OgPanel } from '@/og-panels'

/** Round robin keeps one record from filling a collage; duplicate originals are skipped. */
export function getCardPhotos(moments: readonly Moment[], limit = 4, width = 960) {
  const rows = moments.map((moment) =>
    moment.media.flatMap((media) => {
      const still = media.type === 'video' ? media.poster : media.src
      return still ? [still] : []
    }),
  )
  const seen = new Set<string>()
  const photos: string[] = []
  const depth = Math.max(0, ...rows.map((row) => row.length))
  for (let index = 0; index < depth && photos.length < limit; index++) {
    for (const row of rows) {
      const still = row[index]
      if (!still || seen.has(still.src)) continue
      seen.add(still.src)
      photos.push(
        (still.sources.find((source) => source.width >= width) ?? still.sources.at(-1))?.src ??
          still.src,
      )
      if (photos.length >= limit) break
    }
  }
  return photos
}

export function getCalendarPanel(year: number, month: number, moments: Moment[]): OgPanel {
  const prefix = `${year}-${String(month).padStart(2, '0')}-`
  const byDay = Map.groupBy(
    moments.filter((moment) => moment.dateKey.startsWith(prefix)),
    (moment) => Number(moment.dateKey.slice(-2)),
  )
  return {
    kind: 'calendar',
    year,
    month,
    days: Array.from({ length: new Date(Date.UTC(year, month, 0)).getUTCDate() }, (_, index) => {
      const records = byDay.get(index + 1) ?? []
      return {
        day: index + 1,
        active: records.length > 0,
        photo: getCardPhotos(records, 1, 480)[0],
      }
    }),
  }
}

/** Trips overlap a period even when they began in an earlier month or year. */
export function isTripInPeriod(
  trip: { start: string; end: string },
  period?: { year: number; month?: number | undefined },
) {
  if (!period) return true
  const start = `${period.year}-${String(period.month ?? 1).padStart(2, '0')}`
  const end = `${period.year}-${String(period.month ?? 12).padStart(2, '0')}`
  return trip.start.slice(0, 7) <= end && trip.end.slice(0, 7) >= start
}
