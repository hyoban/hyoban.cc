import type { Messages } from '../messages.ts'

// Numbers and text the cards and the exported video's chapters show. DOM-free.

type TripStats = Readonly<{
  dayCount: number
  drivingKm: number
  drivingHours: number
  photoCount: number
  placeCount: number
  stops: readonly unknown[]
}>

/** The summary's tiles, as `[number, unit]`; the driving ones only when there was driving. */
export function getSummaryStats(trip: TripStats, messages: Messages) {
  const stats = [
    [trip.dayCount, messages.unitDays],
    [trip.stops.length, messages.unitMoments],
    [trip.placeCount, messages.unitPlaces],
    [trip.photoCount, messages.unitPhotos],
    [trip.drivingKm, messages.unitDrivingKm],
    [trip.drivingHours, messages.unitDrivingHours],
  ] as const

  return trip.drivingKm > 0 ? stats : stats.slice(0, 4)
}

/** `m:ss`, the timestamp format video sites read as chapters. */
export function formatChapterTime(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

/**
 * Collects where each chapter starts while a video records. Video sites want
 * chapters at least ten seconds long, so a shorter one gives way to the next.
 */
export function createChapterLog(title: string) {
  const chapters = [{ label: title, time: 0 }]

  return {
    add(label: string, time: number) {
      const last = chapters.at(-1)!

      if (time - last.time < 10) {
        last.label = label
      } else {
        chapters.push({ label, time })
      }
    },
    toString() {
      return `${chapters.map(({ label, time }) => `${formatChapterTime(time)} ${label}`).join('\n')}\n`
    },
  }
}

/**
 * A place's name without what's in brackets, for labels with little room:
 * 眼镜烤肉（老街店） → 眼镜烤肉. A name that is only brackets stays as it is.
 */
export function getShortPlaceName(name: string) {
  return (
    name
      .replaceAll(/[（(][^（）()]*[）)]/g, ' ')
      .replaceAll(/\s+/g, ' ')
      .trim() || name
  )
}
