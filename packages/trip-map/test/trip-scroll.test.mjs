import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { findBetweenMarks, getReadingOffset } from '../src/map/trip-scroll.ts'

test('finds the pair of Moments the reading line is between, and how far', () => {
  const offsets = [100, 200, 200, 400]

  assert.deepEqual(findBetweenMarks(offsets, 50), { fraction: 0, index: -1 })
  assert.deepEqual(findBetweenMarks(offsets, 100), { fraction: 0, index: 0 })
  assert.deepEqual(findBetweenMarks(offsets, 150), { fraction: 0.5, index: 0 })
  // Two Moments at the same offset: the second is reached at once.
  assert.deepEqual(findBetweenMarks(offsets, 300), { fraction: 0.5, index: 2 })
  assert.deepEqual(findBetweenMarks(offsets, 900), { fraction: 1, index: 2 })
  assert.deepEqual(findBetweenMarks([], 0), { fraction: 0, index: -1 })
})

test('keeps the reading line in step until the last screen, then reaches the end', () => {
  const sidebar = { end: 4354, maxScroll: 3706, readingLine: 266, viewport: 664 }

  assert.equal(getReadingOffset({ ...sidebar, scrollTop: 0 }), 266)
  // Halfway down, still at the reading line.
  assert.equal(getReadingOffset({ ...sidebar, scrollTop: 1853 }), 1853 + 266)
  // At the bottom, the last Moment's end.
  assert.equal(getReadingOffset({ ...sidebar, scrollTop: 3706 }), 4354)
  // Nothing to make up: in step all the way.
  assert.equal(getReadingOffset({ ...sidebar, end: 3000, scrollTop: 3706 }), 3706 + 266)
})
