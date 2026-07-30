import assert from 'node:assert/strict'
import test from 'node:test'

import { getCalendarAnniversaries } from '../../src/anniversaries.ts'

test('marks the lunar birthday on its Gregorian date each year', () => {
  assert.deepEqual(getCalendarAnniversaries('2024-09-18'), [{
    description: '25 岁生日（农历八月十六）',
    icon: 'i-lucide-cake-slice',
    id: 'birthday',
    label: '生日 · 25 岁',
  }])
  assert.deepEqual(getCalendarAnniversaries('2025-10-07'), [{
    description: '26 岁生日（农历八月十六）',
    icon: 'i-lucide-cake-slice',
    id: 'birthday',
    label: '生日 · 26 岁',
  }])
  assert.equal(getCalendarAnniversaries('2025-10-08').length, 0)
})

test('marks the delivery date and following anniversaries', () => {
  assert.deepEqual(getCalendarAnniversaries('2025-08-17'), [{
    description: '提车日',
    icon: 'i-lucide-car-front',
    id: 'car',
    label: '提车日',
  }])
  assert.deepEqual(getCalendarAnniversaries('2026-08-17'), [{
    description: '提车 1 周年',
    icon: 'i-lucide-car-front',
    id: 'car',
    label: '提车 1 周年',
  }])
  assert.equal(getCalendarAnniversaries('2026-08-18').length, 0)
})
