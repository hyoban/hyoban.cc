// Numbers and text the cards and the exported video's chapters show. DOM-free.

type TripStats = Readonly<{
  dayCount: number
  distanceKm: number
  drivingHours: number
  photoCount: number
  placeCount: number
  stops: readonly unknown[]
}>

/** The summary's tiles, as `[number, unit]`. */
export function getSummaryStats(trip: TripStats) {
  return [
    [trip.dayCount, '天'],
    [trip.stops.length, '条记录'],
    [trip.placeCount, '个地点'],
    [trip.photoCount, '张照片'],
    [trip.distanceKm, '公里'],
    [trip.drivingHours, '小时车程'],
  ] as const
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
