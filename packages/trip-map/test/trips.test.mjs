import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { getDistanceKm } from '../src/geo.ts'
import { buildTrip, defineTrips, getLegKey } from '../src/trips.ts'

const hefei = { id: 'hefei', latitude: 31.8206, longitude: 117.2272, name: '合肥' }
const nanjing = { id: 'nanjing', latitude: 32.0603, longitude: 118.7969, name: '南京' }
const rizhao = { id: 'rizhao', latitude: 35.4164, longitude: 119.5269, name: '日照' }
const places = { hefei, nanjing, rizhao }
const resolve = (id) => places[id]

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
    { dateKey: '2026-04-30', id: 'before', locations: [hefei], occurredAt: '2026-04-30' },
    { dateKey: '2026-05-01', id: 'start', locations: [hefei], occurredAt: '2026-05-01' },
    { dateKey: '2026-05-02', id: 'unlocated', locations: [], occurredAt: '2026-05-02' },
    { dateKey: '2026-05-03', id: 'end', locations: [nanjing], occurredAt: '2026-05-03' },
    { dateKey: '2026-05-04', id: 'after', locations: [nanjing], occurredAt: '2026-05-04' },
  ]
  const trip = buildTrip(
    { end: '2026-05-03', id: 'trip', start: '2026-05-01', title: 'Trip' },
    moments,
    { resolveLocation: resolve },
  )

  assert.deepEqual(
    trip.stops.map((stop) => stop.id),
    ['start', 'end'],
  )
  assert.equal(trip.dayCount, 3)
  assert.equal(trip.placeCount, 2)
  assert.equal(trip.drivingKm, Math.round(getDistanceKm(hefei, nanjing)))
})

test('routes through every place of a Moment with several places', () => {
  const trip = buildTrip(
    { end: '2026-05-01', id: 'trip', start: '2026-05-01', title: 'Trip' },
    [
      { dateKey: '2026-05-01', id: 'both', locations: [hefei, nanjing], occurredAt: '2026-05-01' },
      { dateKey: '2026-05-01', id: 'back', locations: [hefei], occurredAt: '2026-05-01' },
    ],
    { resolveLocation: resolve },
  )

  assert.equal(trip.placeCount, 2)
  assert.equal(trip.drivingKm, Math.round(getDistanceKm(hefei, nanjing) * 2))
})

test('threads waypoints between stops by time', () => {
  const trip = buildTrip(
    {
      end: '2026-10-01',
      id: 'trip',
      start: '2026-09-30',
      title: 'Trip',
      waypoints: [
        { at: '2026-09-30T18:00:00+08:00', location: 'hefei' },
        { location: 'nanjing' },
        { at: '2026-10-01T20:00:00+08:00', location: 'hefei' },
      ],
    },
    [
      {
        dateKey: '2026-10-01',
        id: 'rizhao',
        locations: [rizhao],
        occurredAt: '2026-10-01T09:00:00+08:00',
      },
    ],
    { resolveLocation: resolve },
  )

  assert.deepEqual(
    trip.stops.map((stop) => [stop.id, stop.waypoints.map((waypoint) => waypoint.location.id)]),
    [['rizhao', ['hefei', 'nanjing']]],
  )
  assert.deepEqual(
    trip.trailingWaypoints.map((waypoint) => waypoint.location.id),
    ['hefei'],
  )
  assert.deepEqual(
    trip.stops[0].waypoints.map((waypoint) => waypoint.time),
    ['18:00', undefined],
  )
  assert.equal(trip.placeCount, 3)
  assert.equal(
    trip.drivingKm,
    Math.round(
      getDistanceKm(hefei, nanjing) + getDistanceKm(nanjing, rizhao) + getDistanceKm(rizhao, hefei),
    ),
  )
})

test('rejects untimed first waypoints and waypoints out of order or range', () => {
  const trip = (waypoints) => [
    { end: '2026-10-02', id: 'trip', start: '2026-10-01', title: 'Trip', waypoints },
  ]

  assert.throws(() => defineTrips(trip([{ location: 'hefei' }])), /needs an "at" time/)
  assert.throws(
    () => defineTrips(trip([{ at: '2026-09-30', location: 'hefei' }])),
    /Invalid waypoint time/,
  )
  assert.throws(
    () =>
      defineTrips(
        trip([
          { at: '2026-10-02', location: 'hefei' },
          { at: '2026-10-01T08:00:00+08:00', location: 'nanjing' },
        ]),
      ),
    /must be in time order/,
  )
})

test('uses cached road routes except on straight legs', () => {
  const moments = [
    { dateKey: '2026-05-01', id: 'hefei', locations: [hefei], occurredAt: '2026-05-01' },
    { dateKey: '2026-05-01', id: 'nanjing', locations: [nanjing], occurredAt: '2026-05-01' },
    { dateKey: '2026-05-02', id: 'rizhao', locations: [rizhao], occurredAt: '2026-05-02' },
  ]
  const route = {
    coordinates: [
      [117.5, 31.9],
      [118.5, 32],
    ],
    distance: 180_000,
    duration: 7200,
  }
  const trip = buildTrip(
    {
      end: '2026-05-02',
      id: 'trip',
      start: '2026-05-01',
      straightLegs: [['rizhao', 'nanjing', 'ferry']],
      title: 'Trip',
    },
    moments,
    {
      resolveLocation: resolve,
      routes: {
        [getLegKey(hefei, nanjing)]: route,
        [getLegKey(nanjing, rizhao)]: route,
      },
    },
  )

  assert.deepEqual(
    trip.legs.map((leg) => [leg.from.id, leg.to.id, leg.by, leg.route === route]),
    [
      ['hefei', 'nanjing', 'car', true],
      ['nanjing', 'rizhao', 'ferry', false],
    ],
  )
  assert.equal(trip.drivingKm, 180)
  // The straight leg reuses no route, so only the drive to Nanjing counts.
  assert.equal(trip.drivingHours, 2)
})

test('counts no driving for a trip taken only by train', () => {
  const moments = [
    { dateKey: '2026-05-01', id: 'hefei', locations: [hefei], occurredAt: '2026-05-01T09:00' },
    { dateKey: '2026-05-01', id: 'nanjing', locations: [nanjing], occurredAt: '2026-05-01T12:00' },
  ]
  const trip = buildTrip(
    {
      end: '2026-05-01',
      id: 'trip',
      start: '2026-05-01',
      straightLegs: [['nanjing', 'hefei', 'train']],
      title: 'Trip',
    },
    moments,
    { resolveLocation: resolve },
  )

  assert.deepEqual(
    trip.legs.map((leg) => leg.by),
    ['train'],
  )
  assert.equal(trip.drivingKm, 0)
  assert.throws(
    () =>
      defineTrips([
        {
          end: '2026-05-01',
          id: 'bus',
          start: '2026-05-01',
          straightLegs: [['hefei', 'nanjing', 'bus']],
          title: 'x',
        },
      ]),
    /Invalid straight leg/,
  )
})

test('drives back the same way along the outbound route, reversed', () => {
  const moments = [
    { dateKey: '2026-05-01', id: 'hefei', locations: [hefei], occurredAt: '2026-05-01T09:00' },
    { dateKey: '2026-05-01', id: 'nanjing', locations: [nanjing], occurredAt: '2026-05-01T12:00' },
    { dateKey: '2026-05-01', id: 'back', locations: [hefei], occurredAt: '2026-05-01T18:00' },
  ]
  const route = {
    coordinates: [
      [117.2, 31.8],
      [118, 32.1],
      [118.8, 32.06],
    ],
    distance: 180_000,
    duration: 7200,
  }
  const trip = buildTrip(
    {
      end: '2026-05-01',
      id: 'trip',
      sameWayBack: [['hefei', 'nanjing']],
      start: '2026-05-01',
      title: 'Trip',
    },
    moments,
    { resolveLocation: resolve, routes: { [getLegKey(hefei, nanjing)]: route } },
  )
  const [there, back] = trip.legs

  assert.equal(there.route, route)
  assert.equal(there.retraced, undefined)
  assert.equal(back.retraced, true)
  assert.deepEqual(back.route.coordinates, [...route.coordinates].reverse())
  assert.equal(trip.drivingKm, 360)
  assert.throws(
    () =>
      defineTrips([
        { end: '2026-01-01', id: 'x', sameWayBack: [['a', 'a']], start: '2026-01-01', title: 'x' },
      ]),
    /Invalid same-way-back/,
  )
})

test('measures great-circle distance in kilometres', () => {
  const distance = getDistanceKm(hefei, nanjing)

  assert.ok(distance > 140 && distance < 160, `unexpected distance ${distance}`)
  assert.equal(getDistanceKm(hefei, hefei), 0)
})
