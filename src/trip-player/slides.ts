// The DOM-free parts of trip playback: which slides a trip plays, how long each one
// lasts, what they're called and which waypoints come before them. Kept apart from
// the player so they can be unit-tested.

type SlideStop = Readonly<{
  date: string
  day: number
  images: readonly unknown[]
  name: string
  places: readonly Readonly<{ name: string }>[]
  text: string
  waypoints: readonly Readonly<{ day: number; name: string }>[]
}>

const INTRO_DURATION = 3000
const DAY_DURATION = 2200
const STOP_DURATION = 5000
const PHOTO_DURATION = 2000
// Roughly the time it takes to read one Chinese character.
const CHARACTER_DURATION = 120

export type TripSlide<S> =
  | { type: 'intro' }
  // Opens each day: `stopIndex` is the day's first stop.
  | { date: string; day: number; places: string[]; stopIndex: number; type: 'day' }
  | { stop: S; stopIndex: number; type: 'stop' }
  | { type: 'summary' }

/** An intro, then each day's opener before its first stop, then a summary. */
export function buildSlides<S extends SlideStop>(stops: readonly S[]): TripSlide<S>[] {
  return [
    { type: 'intro' },
    ...stops.flatMap((stop, stopIndex): TripSlide<S>[] => {
      const slide: TripSlide<S> = { stop, stopIndex, type: 'stop' }

      if (stops[stopIndex - 1]?.day === stop.day) {
        return [slide]
      }

      const dayStops = stops.filter((item) => item.day === stop.day)
      // Waypoints from an earlier day (leaving the night before) aren't this day's places.
      const places = [
        ...new Set(
          dayStops.flatMap((item) =>
            [...item.waypoints.filter((waypoint) => waypoint.day === stop.day), ...item.places].map(
              (place) => place.name,
            ),
          ),
        ),
      ]

      return [{ date: dayStops[0]!.date, day: stop.day, places, stopIndex, type: 'day' }, slide]
    }),
    { type: 'summary' },
  ]
}

/** For each stop, the number of route points up to and including its own places. */
export function getStopPointEnds(stops: readonly SlideStop[]) {
  const ends: number[] = []

  for (const stop of stops) {
    ends.push((ends.at(-1) ?? 0) + stop.waypoints.length + stop.places.length)
  }

  return ends
}

/**
 * The hand-picked covers when there are any, otherwise each day's first photo and
 * then the other stops' first photos.
 */
export function pickCovers<I>(
  covers: readonly I[],
  stops: readonly Readonly<{ day: number; images: readonly I[] }>[],
  count: number,
) {
  if (covers.length > 0) {
    return covers.slice(0, count)
  }

  const firsts = stops.flatMap((stop) =>
    stop.images[0] ? [{ day: stop.day, image: stop.images[0] }] : [],
  )
  const days = new Set<number>()
  const dayFirsts = firsts.filter(({ day }) => !days.has(day) && days.add(day))

  return [...new Set([...dayFirsts, ...firsts].map(({ image }) => image))].slice(0, count)
}

/**
 * How long a slide stays up at normal speed: a stop long enough to show each photo
 * and read its text. The summary has no duration; playback ends there.
 */
export function getSlideDuration(slide: TripSlide<SlideStop> | undefined) {
  if (slide?.type === 'intro') {
    return INTRO_DURATION
  }

  if (slide?.type === 'day') {
    return DAY_DURATION
  }

  return slide?.type === 'stop'
    ? Math.max(
        STOP_DURATION,
        slide.stop.images.length * PHOTO_DURATION,
        slide.stop.text.length * CHARACTER_DURATION,
      )
    : undefined
}

/** Names a slide for the progress bar's tooltip and screen readers. */
export function getSlideLabel(slide: TripSlide<SlideStop>) {
  if (slide.type === 'intro') {
    return '开头'
  }

  if (slide.type === 'day') {
    return `第 ${slide.day} 天 · ${slide.date}`
  }

  return slide.type === 'summary' ? '旅程回顾' : `第 ${slide.stop.day} 天 · ${slide.stop.name}`
}

export function getSlideDay(slide: TripSlide<SlideStop> | undefined) {
  return slide?.type === 'day' ? slide.day : slide?.type === 'stop' ? slide.stop.day : undefined
}

/** `第 N 天 · date · time`, the line above a place's name. */
export function getStopEyebrow({
  date,
  day,
  time,
}: {
  date: string
  day: number
  time?: string | undefined
}) {
  return [`第 ${day} 天`, date, time].filter(Boolean).join(' · ')
}

/** Index of a stop's first route point (its first waypoint, or else its first place). */
export function getStopPointStart(stopPointEnds: readonly number[], stopIndex: number) {
  return stopIndex === 0 ? 0 : stopPointEnds[stopIndex - 1]!
}

/** How many of a stop's waypoints were passed on an earlier day; they come first. */
export function getEarlierDayWaypointCount(stop: SlideStop | undefined) {
  return stop?.waypoints.filter((waypoint) => waypoint.day < stop.day).length ?? 0
}

/**
 * The waypoints the map passes before a slide shows, as `[first point, count]`.
 * Waypoints from the day before a stop (leaving the evening before) are passed
 * before that day's opener, so the new day starts where it really started.
 */
export function getWaypointsBefore(
  slide: TripSlide<SlideStop>,
  stops: readonly SlideStop[],
  stopPointEnds: readonly number[],
  trailingWaypointCount: number,
): [number, number] {
  if (slide.type === 'summary') {
    return [stopPointEnds.at(-1) ?? 0, trailingWaypointCount]
  }

  if (slide.type === 'intro') {
    return [0, 0]
  }

  const start = getStopPointStart(stopPointEnds, slide.stopIndex)
  const stop = stops[slide.stopIndex]!
  const opensDay = stops[slide.stopIndex - 1]?.day !== stop.day
  const earlier = opensDay ? getEarlierDayWaypointCount(stop) : 0

  return slide.type === 'day'
    ? [start, earlier]
    : [start + earlier, stop.waypoints.length - earlier]
}
