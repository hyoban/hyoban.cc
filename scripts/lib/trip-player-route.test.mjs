import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import {
  createChapterLog,
  formatChapterTime,
  getShortPlaceName,
} from '../../src/trip-player/labels.ts'
import { getNextSpeed } from '../../src/trip-player/preferences.ts'
import {
  easeInOut,
  getRouteLine,
  getTravelMode,
  measureLine,
  walkLine,
} from '../../src/trip-player/route.ts'

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
    ratio: 0.5,
    segment: 1,
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

test('eases each stretch in and out so speed never jumps', () => {
  assert.equal(easeInOut(0), 0)
  assert.equal(easeInOut(1), 1)
  assert.ok(Math.abs(easeInOut(0.5) - 0.5) < 1e-9)
  // Nearly at rest at both ends: the first and last 1% cover far less than 1%.
  assert.ok(easeInOut(0.01) < 0.001)
  assert.ok(1 - easeInOut(0.99) < 0.001)
})

test('shortens place names to what is outside brackets', () => {
  assert.equal(getShortPlaceName('眼镜烤肉（老街店）'), '眼镜烤肉')
  assert.equal(getShortPlaceName('火長鳥耕作·燒鳥專門店（高新三号店）'), '火長鳥耕作·燒鳥專門店')
  assert.equal(getShortPlaceName('Cafe (Downtown) Bar'), 'Cafe Bar')
  assert.equal(getShortPlaceName('渤海'), '渤海')
  assert.equal(getShortPlaceName('（未命名）'), '（未命名）')
})
