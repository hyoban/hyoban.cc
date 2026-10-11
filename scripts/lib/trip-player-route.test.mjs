import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { createChapterLog, formatChapterTime } from '../../src/trip-player/labels.ts'
import { getNextSpeed } from '../../src/trip-player/preferences.ts'
import { getRouteLine, getTravelMode, measureLine, walkLine } from '../../src/trip-player/route.ts'

const point = (longitude, latitude) => ({ coordinates: [longitude, latitude] })
const points = [point(0, 0), point(2, 0), point(2, 2)]

test('follows the road between places where one is known', () => {
  const legs = [[[1, 1]], null]

  assert.deepEqual(getRouteLine(points, legs, 0, 3), [
    [0, 0],
    [1, 1],
    [2, 0],
    [2, 2],
  ])
  assert.deepEqual(getRouteLine(points, legs, 1, 3), [
    [2, 0],
    [2, 2],
  ])
})

test('walks a share of the line and stops at the tip', () => {
  const line = measureLine([
    [0, 0],
    [2, 0],
    [2, 2],
  ])

  assert.equal(line.total, 4)
  assert.deepEqual(walkLine(line, 0.25).passed, [[1, 0]])
  assert.deepEqual(walkLine(line, 0.75), {
    end: [2, 2],
    passed: [
      [2, 0],
      [2, 1],
    ],
    start: [2, 0],
  })
  assert.deepEqual(walkLine(line, 2).passed.at(-1), [2, 2])
})

test('tells a ferry or plane leg from driving by its straight segment', () => {
  const modes = ['car', 'ferry']

  assert.equal(getTravelMode(points, modes, [2, 0], [2, 2]), 'ferry')
  assert.equal(getTravelMode(points, modes, [0, 0], [2, 0]), 'car')
  // A road vertex that happens to sit on the ferry's line is still driving.
  assert.equal(getTravelMode(points, modes, [2, 0], [2, 1]), 'car')
})

test('writes chapters video sites accept', () => {
  const log = createChapterLog('国庆东北自驾')

  log.add('第 2 天', 3)
  log.add('第 3 天', 75)
  log.add('旅程回顾', 610)

  assert.equal(formatChapterTime(75), '1:15')
  assert.equal(log.toString(), '0:00 第 2 天\n1:15 第 3 天\n10:10 旅程回顾\n')
})

test('cycles playback speed', () => {
  assert.equal(getNextSpeed(1), 1.5)
  assert.equal(getNextSpeed(2), 1)
  assert.equal(getNextSpeed(3), 1)
})
