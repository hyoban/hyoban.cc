import type { Map as MapboxMap } from 'mapbox-gl'
import { cancelFrame, requestFrame } from '../frames.ts'
import type { Coordinates } from '../geo.ts'
import type { Theme } from '../theme.ts'
import type { Flyover } from './car-model.ts'
import { createRouteLayers } from './route-layers.ts'
import type { RoutePlan } from './route-plan.ts'
import { easeInOut, measureLine, walkLine, type MeasuredLine } from './route.ts'
import { getClock, type ClockMoment, type TripSlide } from './slides.ts'
import type { TripDataStop } from './types.ts'

// Draws the trip's route on the map as playback goes: place by place, each drive
// animated from the last place to the next with the car at its tip, and on a stop's
// card the drive onward timed to the card. Keeps where the route has got to; the
// player decides when to drive.

// Long enough to read a waypoint's name before driving on.
const LEG_DURATION = 2500
// Drawing a drive takes from SHORT_LEG for a hop across town up to LEG_DURATION for
// LONG_LEG_KM or more, so nearby places don't keep the viewer waiting. Even a short
// hop leaves the camera, which eases over most of a second, time to follow it.
const SHORT_LEG = 1500
const LONG_LEG_KM = 400

/** Which places are current, for the stop dots. */
export type RouteStatus = {
  currentStopIndex: number
  currentWaypointPoint: number
  traveling: boolean
}

type Slide = TripSlide<TripDataStop>

export type RouteDrawer = ReturnType<typeof createRouteDrawer>

export function createRouteDrawer({
  flyover,
  getDistanceKm,
  getMap,
  getMotionTime,
  getSpeed,
  getStatus,
  getTheme,
  headClock,
  onClock,
  reducedMotion,
}: {
  /** Whether to fly over, with a car in 3D at the tip. */
  flyover?: Flyover | undefined
  getDistanceKm: (from: Coordinates, to: Coordinates) => number
  getMap: () => MapboxMap | undefined
  /** Milliseconds of motion so far; it stands still while playback is held. */
  getMotionTime: () => number
  getSpeed: () => number
  getStatus: () => RouteStatus
  getTheme: () => Theme
  /** Rides beside the car with the day and time. */
  headClock: HTMLElement
  /** The day and time the tip has reached; `animate` rolls the digits. */
  onClock: (clock: ClockMoment, options: { animate: boolean }) => void
  reducedMotion: MediaQueryList
}) {
  const layers = createRouteLayers(
    getMap,
    () => {
      const theme = getTheme()
      return { ink: theme.ink, paper: theme.paper }
    },
    headClock,
    flyover,
  )
  let plan: RoutePlan | undefined
  let drawnPointCount = 0
  let legFrame: number | undefined
  let finishLeg: (() => void) | undefined
  // Where the route is being drawn up to right now, for the camera to follow.
  let routeTip: Coordinates | undefined
  // The onward drive during a stop's card, timed from when the drive there finished.
  let routeProgress: (MeasuredLine & { startedAt: number; stopIndex: number }) | undefined
  // The stretch of route being drawn now: from `points[from]` toward `points[to]`,
  // `fraction` of the way along `leg`. A card that comes up before it has arrived
  // carries on from there rather than drawing it again.
  let partial: { fraction: number; from: number; leg: MeasuredLine; to: number } | undefined

  function load(nextPlan: RoutePlan) {
    plan = nextPlan
    drawnPointCount = 0
    partial = undefined
    routeTip = undefined
    routeProgress = undefined
  }

  /** Adds the route layers, also after the map style changes and drops them. */
  function addLayers() {
    // The trip has its own arrow, +/- and Home/End keys; the map's would move the camera too.
    if (plan) {
      getMap()?.keyboard.disable()
    }

    if (plan && layers.add(plan.line(0, plan.points.length))) {
      setRoute(drawnPointCount)
      updateStops()
    }
  }

  function remove() {
    cancelLeg()
    layers.remove()
    getMap()?.keyboard.enable()
    plan = undefined
    routeTip = undefined
    routeProgress = undefined
    partial = undefined
  }

  /**
   * Draws the route through the first `pointCount` points, plus the part of the next
   * stretch drawn so far, if one is being drawn: `fraction` of `leg`, which heads
   * for `points[to]`.
   */
  function setRoute(
    pointCount: number,
    piece?: { fraction: number; leg: MeasuredLine; to: number },
  ) {
    if (!plan) {
      return
    }

    const current = plan
    const leg = piece?.leg
    const walked = piece && walkLine(piece.leg, piece.fraction)

    partial = piece && { ...piece, from: pointCount - 1 }
    routeTip = walked?.passed.at(-1)
    // The clock ticks on while the route is drawn, and rolls when it jumps.
    renderClock({ animate: !piece })

    layers.setRoute(
      pointCount,
      () => current.line(0, pointCount),
      leg && walked
        ? {
            line: leg.line,
            mode: current.travelMode(walked.start, walked.end),
            ratio: walked.ratio,
            segment: walked.segment,
          }
        : undefined,
    )
  }

  /** The day and time the route's tip has reached. */
  function getTipClock() {
    const points = plan?.points ?? []

    return partial
      ? getClock(points, partial.from, partial.to, partial.fraction)
      : getClock(points, Math.max(drawnPointCount - 1, 0))
  }

  function renderClock(options: { animate: boolean }) {
    if (plan && plan.points.length > 0) {
      onClock(getTipClock(), options)
    }
  }

  function updateStops() {
    if (!plan) {
      return
    }

    const { currentStopIndex, currentWaypointPoint, traveling } = getStatus()

    layers.setStops(
      plan.points.map((point, pointIndex) => ({
        coordinates: point.coordinates,
        state:
          pointIndex === currentWaypointPoint ||
          (point.stopIndex === currentStopIndex && !point.waypoint && !traveling)
            ? 'current'
            : pointIndex < drawnPointCount
              ? 'visited'
              : 'upcoming',
        waypoint: point.waypoint,
      })),
    )
  }

  function cancelLeg() {
    if (legFrame !== undefined) {
      cancelFrame(legFrame)
      legFrame = undefined
    }

    finishLeg?.()
    finishLeg = undefined
  }

  /** How long drawing the route from `points[from]` to `points[to - 1]` takes. */
  function getDriveTime(from: number, to: number) {
    const start = plan?.points[from]
    const end = plan?.points[to - 1]
    const km = start && end ? getDistanceKm(start.coordinates, end.coordinates) : 0

    // Grows quickly over the first stretch and slowly after, as distance feels.
    return (
      (SHORT_LEG + (LEG_DURATION - SHORT_LEG) * Math.sqrt(Math.min(km / LONG_LEG_KM, 1))) /
      getSpeed()
    )
  }

  /**
   * How the route gets to `points[legEnd - 1]`: on from where the tip is, if the
   * stretch being drawn heads there, or else from `points[legStart]`.
   */
  function planLeg(legStart: number, legEnd: number) {
    const to = legEnd - 1

    if (partial && partial.to === to && partial.from <= legStart && partial.fraction < 1) {
      const { fraction, from, leg } = partial

      return {
        duration: getDriveTime(from, legEnd) * (1 - fraction),
        from,
        leg,
        start: fraction,
        to,
      }
    }

    return {
      duration: getDriveTime(legStart, legEnd),
      from: legStart,
      leg: measureLine(plan?.line(legStart, legEnd) ?? []),
      start: 0,
      to,
    }
  }

  /** Draws the route from `points[legStart]` through every point before `legEnd`. */
  function drawLeg(legStart: number, legEnd: number) {
    cancelLeg()

    return new Promise<void>((resolve) => {
      finishLeg = resolve
      animateLeg(legStart, legEnd)
    })
  }

  function animateLeg(legStart: number, legEnd: number) {
    const { duration, from, leg, start, to } = planLeg(legStart, legEnd)

    if (leg.total === 0 || reducedMotion.matches) {
      drawnPointCount = legEnd
      setRoute(drawnPointCount)
      cancelLeg()
      return
    }

    drawnPointCount = from + 1
    const startTime = getMotionTime()
    // A fresh drive sets off and arrives gently, as the onward drive on each card does;
    // one carried on is already moving, so it only slows to arrive.
    const ease = start > 0 ? (t: number) => 1 - (1 - t) ** 2 : easeInOut
    const step = () => {
      const t = Math.min((getMotionTime() - startTime) / Math.max(duration, 1), 1)

      if (t >= 1) {
        legFrame = undefined
        drawnPointCount = legEnd
        setRoute(drawnPointCount)
        updateStops()
        cancelLeg()
        return
      }

      setRoute(from + 1, { fraction: start + (1 - start) * ease(t), leg, to })
      legFrame = requestFrame(step)
    }

    legFrame = requestFrame(step)
  }

  /**
   * While a stop's card is up, the route keeps moving: from the stop's first place,
   * through its other places, on to the next point, arriving as the card's time ends.
   * `driveElapsed` is how far into the card playback has moved on its own.
   */
  function renderProgress(
    slide: Slide | undefined,
    duration: number | undefined,
    driveElapsed: number,
  ) {
    if (
      !plan ||
      slide?.type !== 'stop' ||
      !duration ||
      getStatus().traveling ||
      legFrame !== undefined
    ) {
      return
    }

    const from = plan.placeStart(slide.stopIndex)
    const to = plan.onwardEnd(slide.stopIndex)

    // The drive here hasn't been drawn yet (the card has only just come up).
    if (drawnPointCount < from + 1) {
      return
    }

    if (routeProgress?.stopIndex !== slide.stopIndex) {
      routeProgress = {
        ...measureLine(plan.line(from, to + 1)),
        startedAt: driveElapsed,
        stopIndex: slide.stopIndex,
      }
    }

    // Starts from rest once any drive here is done, and arrives as the card ends.
    const left = duration - routeProgress.startedAt
    const fraction = left > 0 ? Math.min((driveElapsed - routeProgress.startedAt) / left, 1) : 1

    if (fraction >= 1 || routeProgress.total === 0) {
      drawnPointCount = fraction >= 1 ? to + 1 : from + 1
      setRoute(drawnPointCount)
      return
    }

    drawnPointCount = from + 1
    setRoute(from + 1, { fraction: easeInOut(fraction), leg: routeProgress, to })
  }

  return {
    addLayers,
    cancelLeg,
    drawLeg,
    get drawnPointCount() {
      return drawnPointCount
    },
    set drawnPointCount(count: number) {
      drawnPointCount = count
    },
    get drawing() {
      return legFrame !== undefined
    },
    getTipClock,
    load,
    planLeg,
    remove,
    renderProgress,
    /** A new card starts the onward drive afresh. */
    resetProgress() {
      routeProgress = undefined
    },
    get routeTip() {
      return routeTip
    },
    setRoute,
    updateStops,
  }
}
