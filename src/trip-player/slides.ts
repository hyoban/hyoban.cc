// The DOM-free parts of trip playback: which slides a trip plays, how long each one
// lasts, what they're called and which waypoints come before them. Kept apart from
// the player so they can be unit-tested.

type SlideStop = Readonly<{
  date: string
  day: number
  images: readonly Readonly<{ video?: string | undefined }>[]
  name: string
  places: readonly Readonly<{ name: string }>[]
  text: string
  waypoints: readonly Readonly<{ date: string; day: number; name: string }>[]
}>

// Pacing: the cards with photos keep a brisk rhythm, and everything between them
// (the openers, the drives, the places passed) gets long enough to read and follow.
const INTRO_DURATION = 3000
// A day's opener shows its date, weather and the places ahead: a beat to take in the
// date, then time to read the places, up to a limit for a long day.
const DAY_DURATION = 1600
const DAY_CHARACTER_DURATION = 30
const DAY_MAX_DURATION = 2800
const STOP_DURATION = 3000
const PHOTO_DURATION = 1500
// A live photo's clip runs about 1.3–3.2s (2.8s typically). Its slot lets most of it
// play, the moment in its middle included, without making a trip's video drag.
const LIVE_PHOTO_DURATION = 2400
// Roughly the time it takes to read one Chinese character.
const CHARACTER_DURATION = 120

export type TripSlide<S> =
  | { type: 'intro' }
  | {
      date: string
      day: number
      places: string[]
      /** The next stop: the day's first, or the one a waypoints-only day drives toward. */
      stopIndex: number
      type: 'day'
      /**
       * The waypoints passed before this card shows, as `[first, count]` counted from
       * the stop's first route point: those from the day or days before.
       */
      waypointsBefore: [number, number]
      /** A day with no Moments, only places passed on the way (a travel day). */
      waypointsOnly?: true
    }
  | { stop: S; stopIndex: number; type: 'stop' }
  | { type: 'summary' }

/**
 * An intro, then each day's opener before its first stop, then a summary. A day with
 * only waypoints (driving through without a Moment) gets an opener of its own, and
 * the map passes that day's waypoints while it is up.
 */
export function buildSlides<S extends SlideStop>(stops: readonly S[]): TripSlide<S>[] {
  return [
    { type: 'intro' },
    ...stops.flatMap((stop, stopIndex): TripSlide<S>[] => {
      const slide: TripSlide<S> = { stop, stopIndex, type: 'stop' }
      const previousDay = stops[stopIndex - 1]?.day

      if (previousDay === stop.day) {
        return [slide]
      }

      // Waypoints come in time order; each day between the last stop's and this one
      // that has waypoints is a travel day.
      const earlier = stop.waypoints.slice(0, getEarlierDayWaypointCount(stop))
      const travelDays = [...new Set(earlier.map((waypoint) => waypoint.day))].filter(
        (day) => previousDay === undefined || day > previousDay,
      )
      const firstOfDay = (day: number) => earlier.findIndex((waypoint) => waypoint.day >= day)
      const openers: TripSlide<S>[] = travelDays.map((day, dayIndex) => {
        const from = dayIndex === 0 ? 0 : firstOfDay(travelDays[dayIndex - 1]!)
        const ofDay = earlier.filter((waypoint) => waypoint.day === day)

        return {
          date: ofDay[0]!.date,
          day,
          places: [...new Set(ofDay.map((waypoint) => waypoint.name))],
          stopIndex,
          type: 'day',
          waypointsBefore: [from, firstOfDay(day) - from],
          waypointsOnly: true,
        }
      })
      const ownFrom = travelDays.length > 0 ? firstOfDay(travelDays.at(-1)!) : 0
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

      return [
        ...openers,
        {
          date: dayStops[0]!.date,
          day: stop.day,
          places,
          stopIndex,
          type: 'day',
          waypointsBefore: [ownFrom, earlier.length - ownFrom],
        },
        slide,
      ]
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

/** Whether a stop's live photos play their clips, which gives them longer slots. */
export type SlideTiming = Readonly<{ liveClips: boolean }>

/** How long each of a stop's photos would want to show. */
function getPhotoSlots(stop: SlideStop, { liveClips }: SlideTiming) {
  return stop.images.map((image) =>
    liveClips && image.video ? LIVE_PHOTO_DURATION : PHOTO_DURATION,
  )
}

/**
 * How long a slide stays up at normal speed: a stop long enough to show each photo
 * (a live one until its clip has played) and to read its text. The summary has no
 * duration; playback ends there.
 */
export function getSlideDuration(
  slide: TripSlide<SlideStop> | undefined,
  timing: SlideTiming = { liveClips: false },
) {
  if (slide?.type === 'intro') {
    return INTRO_DURATION
  }

  if (slide?.type === 'day') {
    const characters = slide.places.join('').length
    return Math.min(DAY_DURATION + characters * DAY_CHARACTER_DURATION, DAY_MAX_DURATION)
  }

  return slide?.type === 'stop'
    ? Math.max(
        STOP_DURATION,
        getPhotoSlots(slide.stop, timing).reduce((total, slot) => total + slot, 0),
        slide.stop.text.length * CHARACTER_DURATION,
      )
    : undefined
}

/**
 * Where each of a stop's photos starts, as a share of the time the photos have: their
 * slots, stretched evenly when the stop is longer (to read its text).
 */
export function getPhotoStarts(stop: SlideStop, timing: SlideTiming) {
  const slots = getPhotoSlots(stop, timing)
  const total = slots.reduce((sum, slot) => sum + slot, 0)
  const starts: number[] = []
  let start = 0

  for (const slot of slots) {
    starts.push(total ? start / total : 0)
    start += slot
  }

  return starts
}

/** The photo showing at `share` of the way through a stop's photos. */
export function getPhotoAt(stop: SlideStop, share: number, timing: SlideTiming) {
  const starts = getPhotoStarts(stop, timing)
  return Math.max(
    starts.findLastIndex((start) => start <= share),
    0,
  )
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

/** `第 N 天 · date · time`, naming a stop for screen readers. */
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

export type ClockMoment = { date: string; day: number; time?: string | undefined }

const DAY_MINUTES = 24 * 60

/**
 * The day and time where the route's tip is: `fraction` of the way from `points[from]`
 * to `points[to]`, the clock running on between their times, through midnight into
 * the next day. Between places without a time, or across more than a night (whose
 * dates in between aren't known), it keeps the departure's until it arrives.
 */
export function getClock(
  points: readonly ClockMoment[],
  from: number,
  to = from,
  fraction = 0,
): ClockMoment {
  const departure = points[from]!
  const arrival = points[to] ?? departure

  if (fraction >= 1) {
    return arrival
  }

  const nights = arrival.day - departure.day
  const start = toMinutes(departure.time)
  const end = toMinutes(arrival.time)

  if (
    fraction <= 0 ||
    start === undefined ||
    end === undefined ||
    nights < 0 ||
    nights > 1 ||
    end + nights * DAY_MINUTES < start
  ) {
    return departure
  }

  const minutes = Math.floor(start + (end + nights * DAY_MINUTES - start) * fraction)
  const clock = minutes % DAY_MINUTES
  const time = `${Math.floor(clock / 60)}:${String(clock % 60).padStart(2, '0')}`

  return { ...(minutes >= DAY_MINUTES ? arrival : departure), time }
}

function toMinutes(time: string | undefined) {
  const parts = /^(\d{1,2}):(\d{2})$/.exec(time ?? '')
  return parts ? Number(parts[1]) * 60 + Number(parts[2]) : undefined
}

/** Index of a stop's first route point (its first waypoint, or else its first place). */
export function getStopPointStart(stopPointEnds: readonly number[], stopIndex: number) {
  return stopIndex === 0 ? 0 : stopPointEnds[stopIndex - 1]!
}

/** How many of a stop's waypoints were passed on an earlier day; they come first. */
function getEarlierDayWaypointCount(stop: SlideStop | undefined) {
  return stop?.waypoints.filter((waypoint) => waypoint.day < stop.day).length ?? 0
}

/**
 * The waypoints the map passes before a slide shows, as `[first point, count]`.
 * Waypoints from the day before a stop (leaving the evening before) are passed
 * before that day's opener, so the new day starts where it really started; a travel
 * day's own waypoints are passed while its opener is up.
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

  if (slide.type === 'day') {
    return [start + slide.waypointsBefore[0], slide.waypointsBefore[1]]
  }

  const opensDay = stops[slide.stopIndex - 1]?.day !== stop.day
  const earlier = opensDay ? getEarlierDayWaypointCount(stop) : 0

  return [start + earlier, stop.waypoints.length - earlier]
}
