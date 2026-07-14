import assert from 'node:assert/strict'
import test from 'node:test'

import { getChinaCalendarDay } from '../../src/china-calendar.ts'

test('looks up official days off and adjusted workdays', () => {
  assert.deepEqual(getChinaCalendarDay('2026-06-19'), {
    kind: 'day-off',
    name: '端午',
  })
  assert.deepEqual(getChinaCalendarDay('2026-05-09'), {
    kind: 'workday',
    name: '五一',
  })
  assert.equal(getChinaCalendarDay('2026-07-14'), undefined)
})

test('covers historical and cross-year holiday periods', () => {
  assert.deepEqual(getChinaCalendarDay('2023-04-05'), {
    kind: 'day-off',
    name: '清明',
  })
  assert.deepEqual(getChinaCalendarDay('2023-12-31'), {
    kind: 'day-off',
    name: '元旦',
  })
  assert.deepEqual(getChinaCalendarDay('2025-10-06'), {
    kind: 'day-off',
    name: '国庆、中秋',
  })
})

test('includes period boundaries without marking adjacent dates', () => {
  assert.deepEqual(getChinaCalendarDay('2026-06-21'), {
    kind: 'day-off',
    name: '端午',
  })
  assert.equal(getChinaCalendarDay('2026-06-22'), undefined)
  assert.equal(getChinaCalendarDay('2024-02-09'), undefined)
  assert.deepEqual(getChinaCalendarDay('2024-02-10'), {
    kind: 'day-off',
    name: '新春',
  })
  assert.deepEqual(getChinaCalendarDay('2024-02-17'), {
    kind: 'day-off',
    name: '新春',
  })
})
