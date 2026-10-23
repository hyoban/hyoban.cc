import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import {
  getStretch,
  interpolateMarks,
  locateOnRoute,
  measureRoute,
  sliceRoute,
} from '../src/map/trip-scroll.ts'

// Out along the equator and back the same way.
const route = measureRoute({
  segments: [
    {
      line: [
        [0, 0],
        [1, 0],
        [2, 0],
        [1, 0],
        [0, 0],
      ],
      straight: false,
    },
  ],
})
const leg = route.points[1].distance

test('places a stop on the way back once the way out is behind it', () => {
  assert.equal(locateOnRoute(route, [1, 0.1]), leg)
  assert.equal(locateOnRoute(route, [1, 0.1], leg + 1), leg * 3)
  assert.ok(Math.abs(locateOnRoute(route, [0.5, 0.1]) - leg / 2) < 1e-6)
})

test('draws the route as far as it has been read, ending partway along an edge', () => {
  const { head, lines } = sliceRoute(route, leg * 1.5)

  assert.deepEqual(head, [1.5, 0])
  assert.deepEqual(lines, [
    {
      line: [
        [0, 0],
        [1, 0],
        [1.5, 0],
      ],
      straight: false,
    },
  ])
  assert.deepEqual(sliceRoute(route, 0).lines, [])
})

test('takes the stretch between two places along the route', () => {
  const round = ([longitude, latitude]) => [+longitude.toFixed(6), +latitude.toFixed(6)]

  assert.deepEqual(getStretch(route, leg / 2, leg * 2.5).map(round), [
    [0.5, 0],
    [1, 0],
    [2, 0],
    [1.5, 0],
  ])
})

test('reads the distance between the marks around an offset', () => {
  const marks = [
    { distance: 0, offset: 100 },
    { distance: 10, offset: 200 },
    { distance: 10, offset: 300 },
    { distance: 40, offset: 400 },
  ]

  assert.deepEqual(interpolateMarks(marks, 0), { distance: 0, index: 0 })
  assert.deepEqual(interpolateMarks(marks, 150), { distance: 5, index: 0 })
  assert.deepEqual(interpolateMarks(marks, 250), { distance: 10, index: 1 })
  assert.deepEqual(interpolateMarks(marks, 350), { distance: 25, index: 2 })
  assert.deepEqual(interpolateMarks(marks, 900), { distance: 40, index: 2 })
})
