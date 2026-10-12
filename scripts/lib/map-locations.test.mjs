import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import {
  defineLocations,
  getLocation,
  groupMomentsByLocation,
} from '../../src/moments/map-locations.ts'

test('rejects locations with coordinates outside the public map range', () => {
  assert.throws(
    () =>
      defineLocations({
        invalid: {
          latitude: 31,
          longitude: 181,
          name: 'Invalid place',
        },
      }),
    /Invalid coordinates for location "invalid"/,
  )
})

test('accepts a place in a city and rejects a city that is missing or in a city itself', () => {
  const city = { latitude: 31.35, longitude: 118.43, name: '芜湖' }
  const place = { city: 'wuhu', latitude: 31.3, longitude: 117.91, name: '无为' }

  assert.doesNotThrow(() => defineLocations({ wuhu: city, wuwei: place }))
  assert.throws(() => defineLocations({ wuwei: place }), /isn't a city/)
  assert.throws(
    () => defineLocations({ wuhu: { ...city, city: 'anhui' }, anhui: city, wuwei: place }),
    /isn't a city/,
  )
})

test('rejects moment references to unknown location ids', () => {
  const locations = defineLocations({
    tokyo: {
      latitude: 35.6769,
      longitude: 139.7639,
      name: '东京',
    },
  })

  assert.throws(() => getLocation(locations, 'missing'), /Unknown location id "missing"/)
})

test('groups mapped moments by place and uses the newest occurrence year', () => {
  const locations = defineLocations({
    paris: {
      latitude: 48.8535,
      longitude: 2.3484,
      name: '巴黎',
    },
    tokyo: {
      latitude: 35.6769,
      longitude: 139.7639,
      name: '东京',
    },
  })
  const moments = [
    {
      dateKey: '2025-04-01',
      id: 'tokyo-older',
      locations: [{ id: 'tokyo' }],
      occurredAt: '2025-04-01T16:00:00+08:00',
      order: 1,
    },
    {
      dateKey: '2026-03-10',
      id: 'tokyo-newer',
      locations: [{ id: 'tokyo' }],
      occurredAt: '2026-03-10T16:00:00+08:00',
      order: 1,
    },
    {
      dateKey: '2024-06-20',
      id: 'paris-only',
      locations: [{ id: 'paris' }],
      occurredAt: '2024-06-20',
      order: 1,
    },
    {
      dateKey: '2026-06-01',
      id: 'unmapped',
      locations: [],
      occurredAt: '2026-06-01',
      order: 1,
    },
  ]

  const groups = groupMomentsByLocation(locations, moments)

  assert.deepEqual(
    groups.map((group) => ({
      id: group.id,
      latestYear: group.latestYear,
      momentIds: group.moments.map((moment) => moment.id),
    })),
    [
      {
        id: 'tokyo',
        latestYear: 2026,
        momentIds: ['tokyo-newer', 'tokyo-older'],
      },
      {
        id: 'paris',
        latestYear: 2024,
        momentIds: ['paris-only'],
      },
    ],
  )
})

test('adds a Moment with several places to each place group', () => {
  const locations = defineLocations({
    paris: { latitude: 48.8535, longitude: 2.3484, name: '巴黎' },
    tokyo: { latitude: 35.6769, longitude: 139.7639, name: '东京' },
  })
  const groups = groupMomentsByLocation(locations, [
    {
      dateKey: '2026-03-10',
      id: 'both',
      locations: [{ id: 'tokyo' }, { id: 'paris' }],
      occurredAt: '2026-03-10',
      order: 1,
    },
  ])

  assert.deepEqual(
    groups
      .map((group) => [group.id, group.moments.map((moment) => moment.id)])
      .sort(([first], [second]) => first.localeCompare(second)),
    [
      ['paris', ['both']],
      ['tokyo', ['both']],
    ],
  )
})
