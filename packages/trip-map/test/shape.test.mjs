import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { getTripSummary, shapeTripData } from '../src/shape.ts'
import { buildTrip, defineTrips } from '../src/trips.ts'

const hefei = { id: 'hefei', latitude: 31.8206, longitude: 117.2272, name: '合肥' }
const nanjing = { id: 'nanjing', latitude: 32.0603, longitude: 118.7969, name: '南京' }
const rizhao = { id: 'rizhao', latitude: 35.4164, longitude: 119.5269, name: '日照' }
const places = { hefei, nanjing, rizhao }
const resolve = (id) => places[id]
const [definition] = defineTrips([
  {
    end: '2026-08-03',
    id: 'rizhao',
    start: '2026-08-01',
    straightLegs: [['nanjing', 'rizhao', 'train']],
    title: '去日照',
    waypoints: [{ at: '2026-08-01T08:00:00+08:00', location: 'hefei' }],
  },
])
const moments = [
  {
    dateKey: '2026-08-01',
    id: 'm1',
    locations: [nanjing],
    occurredAt: '2026-08-01T12:00:00+08:00',
  },
  { dateKey: '2026-08-02', id: 'm2', locations: [rizhao], occurredAt: '2026-08-02' },
]
const trip = buildTrip(definition, moments, { resolveLocation: resolve })

test('shapes a built trip into what the player plays', () => {
  const data = shapeTripData(trip, {
    covers: [],
    describeStop: (stop) => ({
      anchor: stop.id,
      href: `/${stop.id}/`,
      images: [],
      text: `at ${stop.locations[0].name}`,
      ...(stop.occurredAt.length > 10 ? { time: stop.occurredAt.slice(11, 16) } : {}),
    }),
    music: '/music.mp3',
    weather: (dateKey) => (dateKey === '2026-08-02' ? { code: 61, max: 30, min: 24 } : undefined),
    driving: (dateKey) =>
      ({
        '2026-08-01': { distanceKm: 336.4, hours: 7.3 },
        '2026-08-02': { distanceKm: 0, hours: 0 },
        '2026-08-03': { distanceKm: 20 },
      })[dateKey],
    charging: (dateKey) => (dateKey === '2026-08-02' ? { count: 2, energyKwh: 45.25 } : undefined),
  })

  assert.equal(data.start, '2026-08-01')
  assert.equal(data.end, '2026-08-03')
  assert.equal(data.dayCount, 3)
  assert.equal(data.music, '/music.mp3')
  assert.deepEqual(
    data.stops.map((stop) => [stop.anchor, stop.day, stop.dateKey, stop.time, stop.places.length]),
    [
      ['m1', 1, '2026-08-01', '12:00', 1],
      ['m2', 2, '2026-08-02', undefined, 1],
    ],
  )
  assert.deepEqual(
    data.stops[0].waypoints,
    [{ ...hefei, id: undefined, dateKey: '2026-08-01', day: 1, time: '08:00' }].map(
      ({ id: _id, ...waypoint }) => waypoint,
    ),
  )
  assert.deepEqual(
    data.legs.map((leg) => leg.mode),
    ['car', 'train'],
  )
  assert.deepEqual(data.weather, { 2: { icon: 'cloud-rain', label: '雨\u200324–30°C' } })
  assert.deepEqual(data.driving, { 1: '开了 336 公里\u20037.3 小时', 3: '开了 20 公里' })
  assert.deepEqual(data.charging, { 2: '充电 2 次\u200345.3 kWh' })
})

test('summarises a trip for the overview, dashing the legs not driven', () => {
  const summary = getTripSummary(trip)

  assert.equal(summary.id, 'rizhao')
  assert.equal(summary.title, '去日照')
  assert.deepEqual(
    summary.segments.map((segment) => [segment.straight, segment.line.length]),
    [
      [false, 2],
      [true, 2],
    ],
  )
})
