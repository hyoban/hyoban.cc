import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { test } from 'vite-plus/test'

import { getCalendarAnniversaries as getSiteAnniversaries } from '../../apps/web/src/anniversaries.ts'
import { readOccasions } from '../../apps/web/src/data/store.ts'

// Made-up occasions, as their entries' parts come with their uid and day, so the tests don't
// depend on the site's own records.
const occasions = [
  {
    uid: '01HZ4V2M00AAAAAAAAAAAAAAB1',
    at: '1999-09-25',
    name: '生日',
    repeat: 'year',
    calendar: 'lunar',
    count: 'age',
  },
  {
    uid: '01HZ4V2M00AAAAAAAAAAAAAAB2',
    at: '2025-08-17',
    name: '提车',
    repeat: 'year',
    count: 'years',
    firstLabel: '提车日',
  },
  {
    uid: '01HZ4V2M00AAAAAAAAAAAAAAB3',
    at: '2022-12-16',
    name: '驾驶证清分',
    repeat: 'year',
  },
  {
    uid: '01HZ4V2M00AAAAAAAAAAAAAAB4',
    at: '2028-12-16',
    name: '驾驶证有效期截止',
  },
]

const getCalendarAnniversaries = (dateKey) => getSiteAnniversaries(dateKey, occasions)

test('marks the lunar birthday on its Gregorian date each year', () => {
  assert.deepEqual(getCalendarAnniversaries('2024-09-18'), [
    {
      description: '25 岁生日（农历八月十六）',
      icon: 'i-lucide-cake-slice',
      id: '01HZ4V2M00AAAAAAAAAAAAAAB1',
      label: '生日 25 岁',
    },
  ])
  assert.deepEqual(getCalendarAnniversaries('2025-10-07'), [
    {
      description: '26 岁生日（农历八月十六）',
      icon: 'i-lucide-cake-slice',
      id: '01HZ4V2M00AAAAAAAAAAAAAAB1',
      label: '生日 26 岁',
    },
  ])
  assert.equal(getCalendarAnniversaries('2025-10-08').length, 0)
  assert.equal(getCalendarAnniversaries('1998-10-06').length, 0)
})

test('marks the first day by its own name and the following anniversaries by their years', () => {
  assert.deepEqual(getCalendarAnniversaries('2025-08-17'), [
    {
      description: '提车日',
      icon: 'i-lucide-party-popper',
      id: '01HZ4V2M00AAAAAAAAAAAAAAB2',
      label: '提车日',
    },
  ])
  assert.deepEqual(getCalendarAnniversaries('2026-08-17'), [
    {
      description: '提车 1 周年',
      icon: 'i-lucide-party-popper',
      id: '01HZ4V2M00AAAAAAAAAAAAAAB2',
      label: '提车 1 周年',
    },
  ])
  assert.equal(getCalendarAnniversaries('2026-08-18').length, 0)
})

test('marks a yearly day not counted by its name, and a day that does not repeat once', () => {
  assert.deepEqual(getCalendarAnniversaries('2027-12-16'), [
    {
      description: '驾驶证清分',
      icon: 'i-lucide-rotate-ccw',
      id: '01HZ4V2M00AAAAAAAAAAAAAAB3',
      label: '驾驶证清分',
    },
  ])
  assert.deepEqual(getCalendarAnniversaries('2028-12-16'), [
    {
      description: '驾驶证清分',
      icon: 'i-lucide-rotate-ccw',
      id: '01HZ4V2M00AAAAAAAAAAAAAAB3',
      label: '驾驶证清分',
    },
    {
      description: '驾驶证有效期截止',
      icon: 'i-lucide-calendar-clock',
      id: '01HZ4V2M00AAAAAAAAAAAAAAB4',
      label: '驾驶证有效期截止',
    },
  ])
  assert.equal(getCalendarAnniversaries('2029-12-16').length, 1)
  assert.equal(getCalendarAnniversaries('2028-12-17').length, 0)
})

test('a note is what a day says of itself', () => {
  const [marked] = getSiteAnniversaries('2026-03-01', [
    { uid: '01HZ4V2M00AAAAAAAAAAAAAAB5', at: '2026-03-01', name: '搬家', note: '搬到新家的日子' },
  ])
  assert.equal(marked.label, '搬家')
  assert.equal(marked.description, '搬到新家的日子')
})

test('the store reads the public occasions, a private one left out', () => {
  const fixtureRoot = fileURLToPath(
    new URL('../../packages/data-spec/test/fixtures/data/', import.meta.url),
  )
  const occasions = readOccasions(fixtureRoot)
  assert.deepEqual(
    occasions.map(({ name, at }) => [name, at]),
    [
      ['开站', '2024-01-20'],
      ['换驾驶证', '2025-06-30'],
    ],
  )
  assert.deepEqual(
    getSiteAnniversaries('2026-01-20', occasions).map((anniversary) => anniversary.label),
    ['开站 2 周年'],
  )
})
