import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { buildSlides, getStopPointEnds, pickCovers } from '../../src/trip-slides.ts'

const place = (name) => ({ name })
const stop = (day, places, waypoints = [], images = []) => ({
  date: `第 ${day} 天`,
  day,
  images,
  places: places.map(place),
  waypoints: waypoints.map(([name, waypointDay]) => ({ day: waypointDay ?? day, name })),
})

test('opens each day before its first stop', () => {
  const stops = [stop(1, ['烟台']), stop(1, ['渤海']), stop(2, ['大连'])]

  assert.deepEqual(
    buildSlides(stops).map((slide) =>
      slide.type === 'stop'
        ? `stop ${slide.stopIndex}`
        : slide.type === 'day'
          ? `day ${slide.day}`
          : slide.type,
    ),
    ['intro', 'day 1', 'stop 0', 'stop 1', 'day 2', 'stop 2', 'summary'],
  )
})

test("lists a day's places once, leaving out waypoints from the night before", () => {
  const stops = [
    stop(1, ['合肥']),
    stop(2, ['南京', '合肥'], [['无为', 1], ['南京']]),
    stop(2, ['南京']),
  ]
  const days = buildSlides(stops).filter((slide) => slide.type === 'day')

  assert.deepEqual(
    days.map(({ date, places, stopIndex }) => [date, places, stopIndex]),
    [
      ['第 1 天', ['合肥'], 0],
      ['第 2 天', ['南京', '合肥'], 1],
    ],
  )
})

test("counts route points up to each stop's own places", () => {
  assert.deepEqual(
    getStopPointEnds([stop(1, ['a']), stop(1, ['b', 'c'], [['w']]), stop(2, [])]),
    [1, 4, 4],
  )
})

test('prefers hand-picked covers', () => {
  assert.deepEqual(pickCovers(['x', 'y', 'z'], [stop(1, ['a'], [], ['p'])], 2), ['x', 'y'])
})

test("falls back to each day's first photo, then the other stops' first photos", () => {
  const stops = [
    stop(1, ['a'], [], ['1a', '1b']),
    stop(1, ['b'], [], ['1c']),
    stop(2, ['c'], [], []),
    stop(3, ['d'], [], ['3a']),
  ]

  assert.deepEqual(pickCovers([], stops, 9), ['1a', '3a', '1c'])
  assert.deepEqual(pickCovers([], stops, 2), ['1a', '3a'])
})
