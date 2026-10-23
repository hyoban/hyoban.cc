import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { findBetweenMarks } from '../src/map/trip-scroll.ts'

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
