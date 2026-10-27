import { describe, expect, test } from 'vite-plus/test'
import type { FogRegions } from '@hyoban/trip-map'
import {
  buildCarFloor,
  buildRouteGlyph,
  buildYearMap,
  getTripLine,
  type TripLine,
} from './year-map.ts'

const square = (id: string, x: number, y: number): FogRegions['features'][number] => ({
  geometry: {
    type: 'Polygon',
    coordinates: [
      [
        [x, y],
        [x + 1, y],
        [x + 1, y + 1],
        [x, y + 1],
        [x, y],
      ],
    ],
  },
  properties: { id, name: id },
})
const regions: FogRegions = {
  features: [square('a', 120, 30), square('b', 122, 30), square('far', 140, 50)],
}
const place = (longitude: number, latitude: number) => ({ id: '', name: '', longitude, latitude })

describe('getTripLine', () => {
  test('follows a leg’s route, or goes straight without one', () => {
    expect(
      getTripLine([
        {
          by: 'car',
          from: place(120, 30),
          to: place(121, 31),
          route: {
            coordinates: [
              [120, 30],
              [120.5, 30.2],
              [121, 31],
            ],
            distance: 1,
          },
        },
        { by: 'plane', from: place(121, 31), to: place(100, 25) },
      ]),
    ).toEqual([
      {
        by: 'car',
        points: [
          [120, 30],
          [120.5, 30.2],
          [121, 31],
        ],
      },
      {
        by: 'plane',
        points: [
          [121, 31],
          [100, 25],
        ],
      },
    ])
  })
})

describe('buildYearMap', () => {
  const map = buildYearMap(
    regions,
    { a: 4, b: 1 },
    [
      {
        id: 'trip',
        lines: [
          {
            by: 'car',
            points: [
              [120.5, 30.5],
              [122.5, 30.5],
            ],
          },
        ],
      },
    ],
    [
      [120.5, 30.5],
      [120.5, 30.5],
      [122.5, 30.5],
    ],
  )

  test('fits what the year reached, leaving out cities beyond it', () => {
    expect(map.width).toBe(1000)
    expect(map.height).toBeGreaterThan(0)
    expect(map.lit.map((city) => city.id)).toEqual(['a', 'b'])
    expect(map.outline.match(/M/g)).toHaveLength(2)
  })

  test('weighs the cities lit by the square root of their share', () => {
    expect(map.lit.map((city) => city.weight)).toEqual([1, 0.5])
  })

  test('draws each leg and one dot a place', () => {
    expect(map.routes[0]!.legs).toHaveLength(1)
    expect(map.routes[0]!.legs[0]!.d).toMatch(/^M[\d.]+ [\d.]+l/)
    expect(map.dots).toHaveLength(2)
  })

  test('bows a flight into an arc', () => {
    const flown = buildYearMap(
      regions,
      { a: 1 },
      [
        {
          id: 'trip',
          lines: [
            {
              by: 'plane',
              points: [
                [120.5, 30.5],
                [122.5, 30.5],
              ],
            },
          ],
        },
      ],
      [],
    )
    expect(flown.routes[0]!.legs[0]!.d).toMatch(/Q/)
  })
})

describe('buildRouteGlyph', () => {
  const drive = (from: number, to: number): TripLine => ({
    by: 'car',
    points: [
      [from, 25],
      [to, 26],
    ],
  })

  test('draws a flown trip from where it landed to where it took off', () => {
    const glyph = buildRouteGlyph([
      drive(120, 121),
      {
        by: 'plane',
        points: [
          [121, 26],
          [100, 25],
        ],
      },
      drive(100, 101),
      {
        by: 'plane',
        points: [
          [101, 26],
          [120, 25],
        ],
      },
    ])
    expect(glyph.d.match(/M/g)).toHaveLength(1)
  })

  test('fits the route into its square', () => {
    const glyph = buildRouteGlyph([drive(120, 121), drive(121, 120)])
    const numbers = glyph.d.match(/[\d.]+/g)!.map(Number)
    expect(Math.max(...numbers)).toBeLessThanOrEqual(100)
    expect(glyph.height).toBeLessThanOrEqual(100)
  })
})

describe('buildCarFloor', () => {
  test('keeps the legs driven in order as its path, without the turns too small to feel', () => {
    const floor = buildCarFloor([
      {
        by: 'car',
        points: [
          [120, 30],
          [120.5, 30.0001],
          [121, 30],
        ],
      },
      {
        by: 'plane',
        points: [
          [121, 30],
          [125, 35],
        ],
      },
      {
        by: 'car',
        points: [
          [121, 30],
          [121, 31],
        ],
      },
    ])
    expect(floor?.path).toHaveLength(2)
    expect(floor?.path[0]).toHaveLength(4)
    // The second leg goes north, up the floor, from where the first ended.
    const [first, second] = floor!.path
    expect(second!.slice(0, 2)).toEqual(first!.slice(2))
    expect(second![3]!).toBeLessThan(second![1]!)
  })

  test('has nothing to stand on without driving', () => {
    expect(
      buildCarFloor([
        {
          by: 'plane',
          points: [
            [120, 30],
            [121, 31],
          ],
        },
      ]),
    ).toBeUndefined()
  })
})
