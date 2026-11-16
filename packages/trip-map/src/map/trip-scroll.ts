import type { Map as MapboxMap, PaddingOptions } from 'mapbox-gl'
import type { Coordinates } from '../geo.ts'
import { getMapPitch } from './basemap.ts'
import type { Messages } from '../messages.ts'
import type { Theme } from '../theme.ts'
import { createCamera } from '../player/camera.ts'
import type { Flyover } from '../player/car-model.ts'
import { createDayHeading } from '../player/day-heading.ts'
import { createElement } from '../player/dom.ts'
import { getShortPlaceName } from '../player/labels.ts'
import { createMapLabels } from '../player/map-labels.ts'
import { NEARBY_MAX_ZOOM, STOP_ZOOM } from '../player/player.ts'
import { measureLine, walkLine, type MeasuredLine } from '../player/route.ts'
import { createRouteLayers } from '../player/route-layers.ts'
import { createRoutePlan, type RoutePlan } from '../player/route-plan.ts'
import { getClock } from '../player/slides.ts'
import type { TripData, TripPoint } from '../player/types.ts'

// A trip played along as its sidebar is read, drawn as the player draws it: the route
// planned, the route driven so far with the car, ship or plane at its tip and the day
// and time beside it, the stop dots, the names of the places of the Moment being read,
// and the player's camera gliding after the tip. Each Moment in the sidebar names its
// stop (`[data-trip-map-stop-entry="<anchor>"]`), and each check-in the waypoint it is
// by its day and minute (`[data-trip-map-waypoint-entry="YYYY-MM-DDTHH:mm"]`); the line a
// little above the sidebar's middle stands for where the reader is: reaching a Moment
// arrives at its stop, a check-in at its waypoint, and reading on drives on to the next
// one, back again scrolling up. After the
// last Moment the route drives on to its end. With the player's flying over on, it flies
// over here too, its car in 3D driving the roads.

// How far down the sidebar the reader is taken to be reading, as a share of its height.
const READING_LINE = 0.4

/**
 * Where along a run of marks (each at an offset) `offset` falls: the pair of marks it's
 * between, by the first's index, and how far from the first to the second, or `index`
 * -1 before the first mark.
 */
export function findBetweenMarks(offsets: readonly number[], offset: number) {
  const last = offsets.length - 1

  if (last < 0 || offset < offsets[0]!) {
    return { fraction: 0, index: -1 }
  }

  for (let index = 0; index < last; index += 1) {
    const start = offsets[index]!
    const end = offsets[index + 1]!

    if (offset < end) {
      return { fraction: end > start ? (offset - start) / (end - start) : 1, index }
    }
  }

  return { fraction: 1, index: Math.max(last - 1, 0) }
}

/**
 * The waypoint among `points` passed at `at` (`YYYY-MM-DDTHH:mm`, a check-in's day and
 * minute), the first one after `after`; -1 when there is none.
 */
export function findWaypointPoint(
  points: readonly Pick<TripPoint, 'dateKey' | 'time' | 'waypoint'>[],
  at: string,
  after = -1,
) {
  return points.findIndex(
    (point, index) => index > after && point.waypoint && `${point.dateKey}T${point.time}` === at,
  )
}

/**
 * Where in the sidebar the reader is, scrolled `scrollTop` down: at the reading line,
 * kept in step with the scrolling. The last Moments may be too near the bottom to reach
 * the line, which then runs on faster over the last screen's scrolling only, so the
 * route is driven to its end (`end`) at the bottom but doesn't run ahead before.
 */
export function getReadingOffset({
  end,
  maxScroll,
  readingLine,
  scrollTop,
  viewport,
}: {
  end: number
  maxScroll: number
  readingLine: number
  scrollTop: number
  viewport: number
}) {
  const short = Math.max(0, end - (maxScroll + readingLine))
  // Over a screen, or longer for a long way to make up: no more than half as fast again.
  const span = Math.min(maxScroll, Math.max(viewport, short * 2))
  const caughtUp = span > 0 ? Math.min(Math.max((scrollTop - (maxScroll - span)) / span, 0), 1) : 1

  return scrollTop + readingLine + short * caughtUp
}

export function createTripScroll({
  content,
  flyover,
  getBlocked,
  getMap,
  getPadding,
  getTheme,
  loadTrip,
  messages,
  onDrawn,
  reducedMotion,
  signal,
}: {
  /** The trip sidebar's scrolling part. */
  content: HTMLElement
  /** The player's flying over, followed here too. */
  flyover?: Flyover | undefined
  /** What's over the map that labels keep out from under: the sidebar, the toolbar. */
  getBlocked: () => DOMRect[]
  getMap: () => MapboxMap | undefined
  /** The camera's room around what it shows, clear of the sidebar. */
  getPadding: () => PaddingOptions
  getTheme: () => Theme
  /** A trip's playback data, as the player loads it. */
  loadTrip: (id: string) => Promise<TripData | undefined>
  messages: Messages
  /** The trip is being played along, or isn't any more (back at the top). */
  onDrawn: (drawn: boolean) => void
  reducedMotion: MediaQueryList
  signal: AbortSignal
}) {
  let tripId: string | undefined
  let trip: TripData | undefined
  let plan: RoutePlan | undefined
  let drawn = false
  // The stretch being driven, measured once: from `points[from]` to `points[to]`, and
  // how far along it, as a share, each point after the first is.
  let leg: (MeasuredLine & { from: number; passed: number[]; to: number }) | undefined
  let tip: Coordinates | undefined
  // The stop whose places are named.
  let labelledStop: string | undefined
  let frame = 0
  let cameraFrame = 0

  const headClock = createElement('div', 'trip-map-head__clock', '')
  const headClockDate = createElement('p', 'trip-map-head__date', '')
  const headClockTime = createElement('p', 'trip-map-head__time', '')
  const clockHeading = createDayHeading(headClockDate, headClockTime, messages)

  headClock.append(headClockDate, headClockTime)

  const layers = createRouteLayers(getMap, getTheme, headClock, flyover)
  const mapLabels = createMapLabels({
    getLayout: () => ({
      avoid: headClock.getClientRects().length > 0 ? [headClock.getBoundingClientRect()] : [],
      blocked: getBlocked(),
      container: getMap()?.getContainer().getBoundingClientRect(),
    }),
    getMap,
    getMarkers: () => [
      ...(plan?.points ?? []).map((point) => ({
        coordinates: point.coordinates,
        radius: point.waypoint ? 7 : 10,
      })),
      ...(tip ? [{ coordinates: tip, radius: 16 }] : []),
    ],
  })
  const camera = createCamera({
    getFlyover: flyover?.isOn,
    getFocus: () => tip ?? (leg && plan?.points[leg.from]?.coordinates),
    getMap,
    getPadding,
    getSpeed: () => 1,
    reducedMotion,
  })

  /**
   * The Moments that are the trip's stops, with their stops' first places, and the
   * check-ins that are its waypoints, with theirs, in the sidebar's order, each further
   * along the route than the one before.
   */
  function readEntries() {
    const top = content.getBoundingClientRect().top - content.scrollTop
    const entries: {
      bottom: number
      point: number
      stopIndex: number
      top: number
      waypoint: boolean
    }[] = []

    for (const element of content.querySelectorAll<HTMLElement>(
      '[data-trip-map-stop-entry], [data-trip-map-waypoint-entry]',
    )) {
      if (!plan) {
        break
      }

      const previous = entries.at(-1)
      const at = element.dataset.tripMapWaypointEntry
      const stopIndex =
        at === undefined
          ? (trip?.stops.findIndex((stop) => stop.anchor === element.dataset.tripMapStopEntry) ??
            -1)
          : -1
      const point =
        at === undefined
          ? stopIndex < 0
            ? -1
            : plan.placeStart(stopIndex)
          : findWaypointPoint(plan.points, at, previous?.point ?? -1)

      if (point < 0 || (previous && point <= previous.point)) {
        continue
      }

      const rect = element.getBoundingClientRect()

      entries.push({
        bottom: rect.bottom - top,
        point,
        stopIndex: at === undefined ? stopIndex : plan.points[point]!.stopIndex,
        top: rect.top - top,
        waypoint: at !== undefined,
      })
    }

    return entries
  }

  /**
   * Where the reader is: the stretch from `points[from]` to `points[to]`, `fraction`
   * of the way along it, and the stop being read; undefined above the first Moment.
   */
  function getPosition() {
    const entries = readEntries()
    const last = entries.at(-1)

    if (!plan || !last) {
      return undefined
    }

    const readingLine = content.clientHeight * READING_LINE
    const maxScroll = content.scrollHeight - content.clientHeight
    const offset = getReadingOffset({
      end: last.bottom,
      maxScroll,
      readingLine,
      scrollTop: content.scrollTop,
      viewport: content.clientHeight,
    })
    const marks = [
      ...entries,
      { ...last, point: Math.max(plan.points.length - 1, last.point), top: last.bottom },
    ]
    let previousOffset = -Infinity
    const offsets = marks.map((mark) => (previousOffset = Math.max(previousOffset, mark.top)))
    const { fraction, index } = findBetweenMarks(offsets, offset)

    if (index < 0) {
      return undefined
    }

    return {
      fraction,
      from: marks[index]!.point,
      stopIndex: marks[index]!.stopIndex,
      to: Math.max(marks[index + 1]!.point, marks[index]!.point),
      waypoint: marks[index]!.waypoint,
    }
  }

  function measureLeg(from: number, to: number) {
    if (leg?.from === from && leg.to === to) {
      return leg
    }

    const line = (end: number) => plan!.line(from, end + 1)
    const measured = measureLine(line(to))
    const passed = []

    for (let point = from + 1; point <= to; point += 1) {
      passed.push(measured.total > 0 ? measureLine(line(point)).total / measured.total : 1)
    }

    leg = { ...measured, from, passed, to }
    return leg
  }

  function draw() {
    frame = 0

    const position = plan && getMap() ? getPosition() : undefined

    if (!position || !plan) {
      clear()
      return
    }

    if (!drawn) {
      drawn = true
      onDrawn(true)
    }

    layers.add(plan.line(0, plan.points.length))

    const current = plan
    const { fraction, from, stopIndex, to, waypoint } = position
    const stretch = measureLeg(from, to)
    const arrived = fraction >= 1 || stretch.total === 0
    const walked = !arrived && fraction > 0 ? walkLine(stretch, fraction) : undefined
    // The places passed on the way, and the one arrived at once the stretch is driven.
    const reached = from + 1 + stretch.passed.filter((share) => arrived || share <= fraction).length
    const drawnTo = arrived && fraction > 0 ? to + 1 : from + 1

    tip = walked?.passed.at(-1)
    layers.setRoute(
      drawnTo,
      () => current.line(0, drawnTo),
      walked
        ? {
            line: stretch.line,
            mode: current.travelMode(walked.start, walked.end),
            ratio: walked.ratio,
            segment: walked.segment,
          }
        : undefined,
    )
    layers.setStops(
      current.points.map((point, pointIndex) => ({
        coordinates: point.coordinates,
        state:
          point.stopIndex === stopIndex && !point.waypoint && !waypoint && !walked
            ? 'current'
            : pointIndex < reached
              ? 'visited'
              : 'upcoming',
        waypoint: point.waypoint,
      })),
    )
    // The clock ticks on while the route is driven, and rolls when it jumps.
    clockHeading.set(getClock(current.points, from, to, walked ? fraction : 0), {
      animate: !walked,
    })

    // A check-in's waypoint is named alone; a Moment's stop, by its places.
    const labelled = waypoint ? `waypoint ${from}` : `stop ${stopIndex}`

    if (labelledStop !== labelled) {
      labelledStop = labelled
      const passed = current.points[from]
      // The day and time ride beside the tip, so labels are just short names.
      mapLabels.show(
        waypoint && passed
          ? [{ coordinates: passed.coordinates, name: getShortPlaceName(passed.name) }]
          : (trip?.stops[stopIndex]?.places ?? []).map((place) => ({
              coordinates: [place.longitude, place.latitude],
              name: getShortPlaceName(place.name),
            })),
        { alignLeft: false },
      )
      followStretch(from, to)
    }

    if (!cameraFrame) {
      cameraFrame = requestAnimationFrame(stepCamera)
    }
  }

  /** As the player follows a stop's drive on: a place with nowhere to go at a city's scale. */
  function followStretch(from: number, to: number) {
    const stretch = plan?.line(from, to + 1) ?? []
    const spread = stretch.some(
      ([longitude, latitude]) => longitude !== stretch[0]![0] || latitude !== stretch[0]![1],
    )

    camera.follow(stretch, spread ? NEARBY_MAX_ZOOM : STOP_ZOOM)
  }

  function stepCamera(now: number) {
    camera.update(now)
    cameraFrame = drawn ? requestAnimationFrame(stepCamera) : 0
  }

  /** Takes everything off the map: the reader is above the first Moment, or gone. */
  function clear() {
    leg = undefined
    tip = undefined
    labelledStop = undefined

    // The player draws on the same layers: they're only this one's while it's drawn.
    if (!drawn) {
      return
    }

    cancelAnimationFrame(cameraFrame)
    cameraFrame = 0
    camera.stop()
    uprightMap()
    mapLabels.clear()
    layers.remove()
    drawn = false
    onDrawn(false)
  }

  /**
   * The map as it was before the camera followed the route: flown over, it was tipped back
   * and turned the drive's way, and comes upright again, leaning as it does at its zoom.
   */
  function uprightMap() {
    const map = getMap()

    if (!map) {
      return
    }

    const pitch = getMapPitch(map, map.getZoom())

    if (Math.abs(map.getBearing()) > 0.5 || Math.abs(map.getPitch() - pitch) > 0.5) {
      map.easeTo({ bearing: 0, duration: reducedMotion.matches ? 0 : 600, pitch })
    }
  }

  function schedule() {
    if (plan && !frame) {
      frame = requestAnimationFrame(draw)
    }
  }

  /** Puts the layers back after the map's style changes and drops them. */
  function add() {
    if (drawn) {
      layers.remove()
      schedule()
    }
  }

  /** Follows the sidebar's scrolling for a trip, once its playback data is here. */
  function start(id: string) {
    if (tripId === id) {
      schedule()
      return
    }

    stop()
    tripId = id
    void loadTrip(id).then((data) => {
      if (data && tripId === id) {
        trip = data
        plan = createRoutePlan(data)
        schedule()
      }
    })
  }

  /** Stops following, taking the trip off the map. */
  function stop() {
    tripId = undefined
    trip = undefined
    plan = undefined
    cancelAnimationFrame(frame)
    frame = 0
    clear()
  }

  content.addEventListener('scroll', schedule, { passive: true, signal })
  window.addEventListener('resize', schedule, { passive: true, signal })
  signal.addEventListener('abort', () => {
    cancelAnimationFrame(frame)
    cancelAnimationFrame(cameraFrame)
    mapLabels.destroy()
  })

  return {
    add,
    /** Whether the trip is being played along, its route on the map. */
    get drawn() {
      return drawn
    },
    start,
    stop,
  }
}
