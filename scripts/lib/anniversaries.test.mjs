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

test('marks the annual driver-license reset and its fixed expiry date', () => {
  assert.deepEqual(getCalendarAnniversaries('2027-12-16'), [{
    description: '驾驶证清分',
    icon: 'i-lucide-rotate-ccw',
    id: 'driver-license-points-reset',
    label: '驾驶证清分',
  }])
  assert.deepEqual(getCalendarAnniversaries('2028-12-16'), [
    {
      description: '驾驶证清分',
      icon: 'i-lucide-rotate-ccw',
      id: 'driver-license-points-reset',
      label: '驾驶证清分',
    },
    {
      description: '驾驶证有效期截止',
      icon: 'i-lucide-calendar-clock',
      id: 'driver-license-expiry',
      label: '驾驶证有效期截止',
    },
  ])
  assert.equal(getCalendarAnniversaries('2029-12-16').length, 1)
  assert.equal(getCalendarAnniversaries('2028-12-17').length, 0)
})
