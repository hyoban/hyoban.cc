// The DOM-free parts of trip playback: which slides a trip plays and which photos
// stand for it. Kept apart from calendar-map-trip.ts so they can be unit-tested.

type SlideStop = Readonly<{
  date: string
  day: number
  images: readonly unknown[]
  places: readonly Readonly<{ name: string }>[]
  waypoints: readonly Readonly<{ day: number; name: string }>[]
}>

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
