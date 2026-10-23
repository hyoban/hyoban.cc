import { describe, expect, test } from 'vite-plus/test'
import type { FogRegions } from '@hyoban/trip-map'
import {
  buildYearReview,
  getCityName,
  getLongestStreak,
  getReviewYears,
  getYearPlaces,
} from './year-review.ts'

const square = (id: string, name: string, x: number): FogRegions['features'][number] => ({
  geometry: {
    type: 'Polygon',
    coordinates: [
      [
        [x, 0],
        [x + 1, 0],
        [x + 1, 1],
        [x, 1],
        [x, 0],
      ],
    ],
  },
  properties: { id, name },
})
const cities: FogRegions = {
  features: [square('320500', '苏州市', 0), square('340100', '合肥市', 2)],
}
const suzhou = { id: 'suzhou', name: '苏州', latitude: 0.5, longitude: 0.5 }
const hefei = { id: 'hefei', name: '合肥', latitude: 0.5, longitude: 2.5 }
const photo = { type: 'image' as const }

const moment = (occurredAt: string, locations = [suzhou], media: object[] = [], topic?: string) =>
  ({
    ...(topic ? { topic } : {}),
    dateKey: occurredAt.slice(0, 10),
    id: occurredAt,
    locations,
    media,
    occurredAt,
    ...(occurredAt.length > 10 ? { occurredLabel: occurredAt.slice(11, 16) } : {}),
    text: '',
  }) as never

describe('getLongestStreak', () => {
  test('finds the first longest run of consecutive days', () => {
    expect(
      getLongestStreak(['2026-01-30', '2026-01-31', '2026-02-01', '2026-02-05', '2026-02-06']),
    ).toEqual({ start: '2026-01-30', end: '2026-02-01', days: 3 })
  })

  test('is empty without days', () => {
    expect(getLongestStreak([])).toEqual({ start: '', end: '', days: 0 })
  })
})

test('getCityName drops a city’s suffix unless that leaves one character', () => {
  expect(getCityName('苏州市')).toBe('苏州')
  expect(getCityName('迪庆藏族自治州')).toBe('迪庆')
  expect(getCityName('黔东南苗族侗族自治州')).toBe('黔东南')
  expect(getCityName('阿里地区')).toBe('阿里')
  expect(getCityName('沙市')).toBe('沙市')
})

test('getReviewYears lists the years with a Moment', () => {
  expect(getReviewYears([{ dateKey: '2026-01-01' }, { dateKey: '2024-05-01' }])).toEqual([
    2024, 2026,
  ])
})

describe('buildYearReview', () => {
  const moments = [
    moment('2025-06-01', [suzhou]),
    moment('2026-01-01T22:10:00+08:00', [suzhou], [photo]),
    moment('2026-01-02T22:40:00+08:00', [hefei], [photo, photo, { type: 'video' }], 'travel'),
    moment('2026-01-02', [], [], 'food'),
    moment('2026-03-10T09:00:00+08:00', [hefei]),
  ]
  const trips = [
    {
      id: '2026-anhui',
      title: '合肥',
      start: '2026-01-02',
      end: '2026-01-03',
      dayCount: 2,
      drivingKm: 210,
      placeCount: 2,
    },
    {
      id: '2025-old',
      title: '旧',
      start: '2025-06-01',
      end: '2025-06-01',
      dayCount: 1,
      drivingKm: 5,
      placeCount: 1,
    },
  ]
  const review = buildYearReview(2026, moments, trips, cities)

  test('counts the year’s Moments, media and days', () => {
    expect(review.moments).toHaveLength(4)
    expect(review.photoCount).toBe(3)
    expect(review.videoCount).toBe(1)
    expect(review.dayCount).toBe(3)
    expect(review.streak).toEqual({ start: '2026-01-01', end: '2026-01-02', days: 2 })
    expect(review.busiestDay).toEqual({ dateKey: '2026-01-02', count: 2 })
    expect(review.favoriteHour).toEqual({ hour: 22, count: 2 })
  })

  test('counts the Moments with a time by hour', () => {
    expect(review.hours).toHaveLength(24)
    expect(review.hours[22]).toBe(2)
    expect(review.hours[9]).toBe(1)
  })

  test('counts the Moments by day of the week, Monday first', () => {
    expect(review.weekdays).toEqual([0, 1, 0, 1, 2, 0, 0])
  })

  test('counts the topics against the year before, without one counting as life', () => {
    expect(review.topics).toEqual([
      { topic: 'travel', count: 1, previous: 0 },
      { topic: 'food', count: 1, previous: 0 },
      { topic: 'life', count: 2, previous: 1 },
    ])
  })

  test('keeps the trips started in the year', () => {
    expect(review.trips.map((trip) => trip.id)).toEqual(['2026-anhui'])
    expect(review.drivingKm).toBe(210)
  })

  test('tells the cities seen for the first time', () => {
    expect(review.cities.lit.map((city) => [city.name, city.count])).toEqual([
      ['合肥', 2],
      ['苏州', 1],
    ])
    expect(review.cities.newNames).toEqual(['合肥'])
    expect(review.firstVisits).toEqual([{ name: '合肥', dateKey: '2026-01-02' }])
  })
})

test('getYearPlaces counts each place once per Moment, in the order first reached, with its kind and division', () => {
  const lake = { id: 'lake', name: '湖', latitude: 0.5, longitude: 0.6 }
  const places = {
    suzhou: {
      ...suzhou,
      kind: 'city' as const,
      division: { code: '320500', province: '江苏省', city: '苏州市' },
    },
    lake: { ...lake, kind: 'scenic' as const },
  }

  expect(
    getYearPlaces(
      [
        moment('2026-03-01', [suzhou]),
        moment('2026-03-02', [lake, suzhou]),
        moment('2026-03-05', [hefei]),
      ],
      places,
    ),
  ).toEqual([
    {
      id: 'suzhou',
      name: '苏州',
      kind: 'city',
      division: { code: '320500', province: '江苏省', city: '苏州市' },
      count: 2,
      dateKey: '2026-03-01',
    },
    { id: 'lake', name: '湖', kind: 'scenic', count: 1, dateKey: '2026-03-02' },
    { id: 'hefei', name: '合肥', count: 1, dateKey: '2026-03-05' },
  ])
})
