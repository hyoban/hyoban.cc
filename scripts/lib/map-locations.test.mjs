import assert from 'node:assert/strict'
import test from 'node:test'
import {
  defineLocations,
  getLocation,
  groupMomentsByLocation,
} from '../../src/map-locations.ts'

test('rejects locations with coordinates outside the public map range', () => {
  assert.throws(
    () => defineLocations({
      invalid: {
        latitude: 31,
        longitude: 181,
        name: 'Invalid place',
      },
    }),
    /Invalid coordinates for location "invalid"/,
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

  assert.throws(
    () => getLocation(locations, 'missing'),
    /Unknown location id "missing"/,
  )
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
      locationId: 'tokyo',
      publishedAt: new Date('2025-04-01T08:00:00Z'),
    },
    {
      dateKey: '2026-03-10',
      id: 'tokyo-newer',
      locationId: 'tokyo',
      publishedAt: new Date('2026-03-10T08:00:00Z'),
    },
    {
      dateKey: '2024-06-20',
      id: 'paris-only',
      locationId: 'paris',
      publishedAt: new Date('2024-06-20T08:00:00Z'),
    },
    {
      dateKey: '2026-06-01',
      id: 'unmapped',
      publishedAt: new Date('2026-06-01T08:00:00Z'),
    },
  ]

  const groups = groupMomentsByLocation(locations, moments)

  assert.deepEqual(
    groups.map(group => ({
      id: group.id,
      latestYear: group.latestYear,
      momentIds: group.moments.map(moment => moment.id),
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
