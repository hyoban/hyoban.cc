import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { buildTrip, defineTrips, getDistanceKm } from '../../src/moments/trips.ts'

const hefei = { id: 'hefei', latitude: 31.8206, longitude: 117.2272, name: '合肥' }
const nanjing = { id: 'nanjing', latitude: 32.0603, longitude: 118.7969, name: '南京' }

test('rejects invalid, reversed and duplicate trips', () => {
  assert.throws(
    () => defineTrips([{ end: '2026-01-02', id: 'Bad Id', start: '2026-01-01', title: 'x' }]),
    /Invalid trip id/,
  )
  assert.throws(
    () => defineTrips([{ end: '2026-01-01', id: 'reversed', start: '2026-01-02', title: 'x' }]),
    /Invalid date range/,
  )
  assert.throws(
    () =>
      defineTrips([
        { end: '2026-01-01', id: 'same', start: '2026-01-01', title: 'x' },
        { end: '2026-01-02', id: 'same', start: '2026-01-02', title: 'y' },
      ]),
    /Duplicate trip id/,
  )
})

test('keeps located moments inside the inclusive date range', () => {
  const moments = [
    { dateKey: '2026-04-30', id: 'before', location: hefei },
    { dateKey: '2026-05-01', id: 'start', location: hefei },
    { dateKey: '2026-05-02', id: 'unlocated' },
    { dateKey: '2026-05-03', id: 'end', location: nanjing },
    { dateKey: '2026-05-04', id: 'after', location: nanjing },
  ]
  const trip = buildTrip(
    { end: '2026-05-03', id: 'trip', start: '2026-05-01', title: 'Trip' },
    moments,
  )

  assert.deepEqual(
    trip.stops.map((stop) => stop.id),
    ['start', 'end'],
  )
  assert.equal(trip.dayCount, 3)
  assert.equal(trip.placeCount, 2)
  assert.equal(trip.distanceKm, Math.round(getDistanceKm(hefei, nanjing)))
})

test('measures great-circle distance in kilometres', () => {
  const distance = getDistanceKm(hefei, nanjing)

  assert.ok(distance > 140 && distance < 160, `unexpected distance ${distance}`)
  assert.equal(getDistanceKm(hefei, hefei), 0)
})
