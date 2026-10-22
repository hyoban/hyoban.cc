import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { isInPeriod, isSamePeriod, overlapsPeriod } from '../src/map/period.ts'

test('tells the days and months in a year or a month', () => {
  assert.equal(isInPeriod('2025-06-12', { year: 2025 }), true)
  assert.equal(isInPeriod('2025-06', { month: 6, year: 2025 }), true)
  assert.equal(isInPeriod('2025-07-01', { month: 6, year: 2025 }), false)
  assert.equal(isInPeriod('2024-06-12', { year: 2025 }), false)
})

test('tells a trip under way in a period, by any of its days', () => {
  assert.equal(overlapsPeriod('2024-12-28', '2025-01-03', { year: 2025 }), true)
  assert.equal(overlapsPeriod('2024-12-28', '2025-01-03', { month: 12, year: 2024 }), true)
  assert.equal(overlapsPeriod('2024-12-28', '2025-01-03', { month: 2, year: 2025 }), false)
  assert.equal(overlapsPeriod('2025-05-30', '2025-07-02', { month: 6, year: 2025 }), true)
})

test('compares periods, none included', () => {
  assert.equal(isSamePeriod({ year: 2025 }, { month: undefined, year: 2025 }), true)
  assert.equal(isSamePeriod({ year: 2025 }, { month: 6, year: 2025 }), false)
  assert.equal(isSamePeriod(undefined, undefined), true)
})
