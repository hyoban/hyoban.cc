import { getPhotoStarts, type SlideTiming, type TripSlide } from './slides.ts'

// Where playback goes next, worked out without the DOM: which slide a request lands
// on, where a day starts, which card a shared link names. The player keeps the time
// and drives the map; these only decide.

type Stop = { anchor: string; images: readonly unknown[] }
type Slide = TripSlide<
  Stop & {
    day: number
    dateKey: string
    places: readonly { name: string }[]
    text: string
    waypoints: readonly { dateKey: string; day: number; name: string }[]
  }
>

/**
 * The slide a request for `requested` lands on. Presenting or recording, the header's
 * rolling day stands in for a day's opener, so those are skipped in the direction of travel.
 */
export function resolveSlideIndex(
  slides: readonly Slide[],
  index: number,
  requested: number,
  stageLayout: boolean,
) {
  let next = requested

  if (stageLayout) {
    const direction = requested < index ? -1 : 1

    while (slides[next]?.type === 'day') {
      next += direction
    }
  }

  return next
}

/** The next day's opener, or back to this day's (then the one before); the ends when there is none. */
export function findDayTarget(
  slides: readonly Slide[],
  index: number,
  direction: number,
  stageLayout: boolean,
) {
  // Presenting or recording skips the openers, so a day starts at the stop after them.
  const startsDay = stageLayout
    ? (slide: Slide, slideIndex: number) =>
        slide.type !== 'day' && slides[slideIndex - 1]?.type === 'day'
    : (slide: Slide) => slide.type === 'day'
  const openers = slides.flatMap((slide, slideIndex) =>
    startsDay(slide, slideIndex) ? [slideIndex] : [],
  )
  const target =
    direction > 0
      ? openers.find((slideIndex) => slideIndex > index)
      : openers.findLast((slideIndex) => slideIndex < index)

  return target ?? (direction > 0 ? slides.length - 1 : 0)
}

/** The slide a shared link's anchor names: a stop's card, or its day's opener when it starts the day. */
export function findAnchorSlide(slides: readonly Slide[], stops: readonly Stop[], anchor: string) {
  const stopIndex = stops.findIndex((stop) => stop.anchor === anchor)

  if (!anchor || stopIndex === -1) {
    return 0
  }

  // A travel day before the stop isn't what the link names; its own day is.
  return Math.max(
    slides.findIndex(
      (slide) =>
        ((slide.type === 'day' && !slide.waypointsOnly) || slide.type === 'stop') &&
        slide.stopIndex === stopIndex,
    ),
    0,
  )
}

/** The anchor a slide is shared by, or none for the intro, the summary and travel days. */
export function getSlideAnchor(slide: Slide, stops: readonly Stop[]) {
  return slide.type === 'stop' || (slide.type === 'day' && !slide.waypointsOnly)
    ? (stops[slide.stopIndex]?.anchor ?? '')
    : ''
}

/** How full a progress segment is: past ones full, the current one as far as it has played. */
export function getProgressAmount(
  itemIndex: number,
  index: number,
  elapsed: number,
  duration: number | undefined,
) {
  if (itemIndex < index) {
    return 1
  }

  if (itemIndex > index) {
    return 0
  }

  return duration ? Math.min(elapsed / duration, 1) : 1
}

/** The card's time at which a stop's photo starts showing, with the drive there (`delay`) before it. */
export function getPhotoTime(
  stop: Parameters<typeof getPhotoStarts>[0],
  imageIndex: number,
  duration: number,
  delay: number,
  timing: SlideTiming,
) {
  const start = getPhotoStarts(stop, timing)[imageIndex] ?? 0
  return delay + (duration - delay) * start
}

/**
 * Where a step through a stop's photos goes: the photo `direction` away, or past the
 * ends the next or previous card, landing on the previous card's last photo.
 */
export function stepThroughPhotos(photoCount: number, photoIndex: number, direction: 1 | -1) {
  const next = photoIndex + direction

  return next >= 0 && next < photoCount
    ? ({ photo: next, type: 'photo' } as const)
    : ({ direction, type: 'card' } as const)
}
