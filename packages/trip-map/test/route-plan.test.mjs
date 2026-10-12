import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { createRoutePlan } from '../src/player/route-plan.ts'

const place = (name, longitude, latitude) => ({ latitude, longitude, name })
const waypoint = (name, longitude, latitude, day) => ({
  ...place(name, longitude, latitude),
  dateKey: `2026-10-0${day}`,
  day,
})
const trip = {
  covers: [],
  dayCount: 2,
  drivingHours: 0,
  drivingKm: 0,
  end: '2026-10-02',
  id: 'test',
  legs: [
    { line: [[0.5, 0.5]], mode: 'car' },
    { line: null, mode: 'car' },
    { line: null, mode: 'ferry' },
    { line: null, mode: 'car' },
  ],
  photoCount: 0,
  placeCount: 4,
  start: '2026-10-01',
  stops: [
    {
      anchor: 'a',
      dateKey: '2026-10-01',
      day: 1,
      href: '/a',
      images: [],
      places: [place('A', 1, 1)],
      text: '',
      waypoints: [waypoint('W', 0, 0, 1)],
    },
    {
      anchor: 'b',
      dateKey: '2026-10-02',
      day: 2,
      href: '/b',
      images: [],
      places: [place('B', 2, 2), place('B2', 3, 3)],
      text: '',
      waypoints: [],
    },
  ],
  title: 'Test',
  trailingWaypoints: [waypoint('End', 4, 4, 2)],
  weather: {},
}

test('lists every place in visiting order and knows where each stop starts', () => {
  const plan = createRoutePlan(trip)

  assert.deepEqual(
    plan.points.map((point) => [point.name, point.waypoint, point.stopIndex]),
    [
      ['W', true, 0],
      ['A', false, 0],
      ['B', false, 1],
      ['B2', false, 1],
      ['End', true, 2],
    ],
  )
  assert.deepEqual(plan.stopPointEnds, [2, 4])
  assert.equal(plan.pointStart(0), 0)
  assert.equal(plan.placeStart(0), 1)
  assert.equal(plan.pointStart(1), 2)
  assert.equal(plan.placeStart(1), 2)
  // The card for the last stop drives on to the trailing waypoint, the route's end.
  assert.equal(plan.onwardEnd(1), 4)
})

test('follows the road where one is cached and goes straight otherwise', () => {
  const plan = createRoutePlan(trip)

  assert.deepEqual(plan.line(0, 3), [
    [0, 0],
    [0.5, 0.5],
    [1, 1],
    [2, 2],
  ])
  assert.equal(plan.travelMode([2, 2], [3, 3]), 'ferry')
  assert.equal(plan.travelMode([1, 1], [2, 2]), 'car')
})

test('answers which waypoints a slide passes before it shows', () => {
  const plan = createRoutePlan(trip)
  const [, day1, stopA, , stopB, summary] = plan.slides

  assert.deepEqual(plan.waypointsBefore(day1), [0, 0])
  assert.deepEqual(plan.waypointsBefore(stopA), [0, 1])
  assert.deepEqual(plan.waypointsBefore(stopB), [2, 0])
  assert.deepEqual(plan.waypointsBefore(summary), [4, 1])
})
