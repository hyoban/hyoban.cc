import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { buildFogOfWar } from '../src/fog-of-war.ts'

// A city is lit by a place inside it, and not by one in a hole of it or outside it.

const square = (west, south, east, north) => [
  [west, south],
  [east, south],
  [east, north],
  [west, north],
  [west, south],
]

const regions = {
  features: [
    {
      // A city with a lake in it.
      geometry: { coordinates: [square(0, 0, 10, 10), square(4, 4, 6, 6)], type: 'Polygon' },
      properties: { id: '320100', name: '南京市' },
    },
    {
      // A city of two parts, in the same province.
      geometry: {
        coordinates: [[square(10, 0, 20, 10)], [square(30, 0, 31, 1)]],
        type: 'MultiPolygon',
      },
      properties: { id: '320200', name: '无锡市' },
    },
    {
      geometry: { coordinates: [square(0, 10, 10, 20)], type: 'Polygon' },
      properties: { id: '340100', name: '合肥市' },
    },
    {
      // The rest of the world, never lit.
      geometry: {
        coordinates: [[square(-180, -85, 180, 85), square(0, 0, 31, 20).toReversed()]],
        type: 'MultiPolygon',
      },
      properties: { fogged: true, id: 'outside', name: '' },
    },
  ],
}

test('nothing been to lights nothing', () => {
  assert.deepEqual(buildFogOfWar(regions, []), { cities: 0, lit: [], provinces: 0, weights: {} })
})

test('a place lights the city it is in, once however many are there', () => {
  assert.deepEqual(buildFogOfWar(regions, [{ latitude: 2, longitude: 2 }, [3, 3], [30.5, 0.5]]), {
    cities: 2,
    lit: ['320100', '320200'],
    provinces: 1,
    weights: { 320100: 2, 320200: 1 },
  })
})

test('a city holds the weight of every place in it', () => {
  assert.deepEqual(
    buildFogOfWar(regions, [
      { latitude: 2, longitude: 2, weight: 3 },
      { latitude: 8, longitude: 8, weight: 4 },
      [12, 2],
    ]).weights,
    { 320100: 7, 320200: 1 },
  )
})

test('a place just off a city, on the coast, lights it', () => {
  assert.deepEqual(buildFogOfWar(regions, [[-0.05, 3]]).lit, ['320100'])
})

test("a place in a city's hole or far from any city lights nothing", () => {
  assert.deepEqual(
    buildFogOfWar(regions, [
      [5, 5],
      [50, 50],
    ]).lit,
    [],
  )
})

test('cities in two provinces count two', () => {
  assert.equal(
    buildFogOfWar(regions, [
      [1, 1],
      [1, 11],
    ]).provinces,
    2,
  )
})
