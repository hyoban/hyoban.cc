import { describe, expect, test } from 'vite-plus/test'
import { findOutings, getDistanceLevel, getYearWeeks, type VehicleDistanceDay } from './vehicle.ts'

const day = (date: string, kilometers: number): VehicleDistanceDay => ({
  date,
  distanceMeters: kilometers * 1000,
})

describe('findOutings', () => {
  test('joins long days a rest day apart and drops short runs', () => {
    const days = [
      day('2026-03-01', 200),
      day('2026-03-02', 20),
      day('2026-03-03', 180),
      day('2026-03-10', 160),
      day('2026-03-20', 400),
      day('2026-03-23', 170),
    ]
    const outings = findOutings(days, [], [], [])

    expect(outings.map(({ distanceMeters, end, start }) => [start, end, distanceMeters])).toEqual([
      ['2026-03-20', '2026-03-20', 400_000],
      ['2026-03-01', '2026-03-03', 400_000],
    ])
  })

  test('takes a trip’s dates and every run it overlaps', () => {
    const trip = { end: '2026-05-05', id: 'may-day', start: '2026-05-01', title: '五一' }
    const days = [day('2026-04-30', 50), day('2026-05-01', 500), day('2026-05-05', 160)]
    const [outing] = findOutings(days, [], [trip], [])

    expect(outing).toMatchObject({
      distanceMeters: 660_000,
      drivenDays: 2,
      end: '2026-05-05',
      start: '2026-05-01',
      trip,
    })
  })

  test('keeps a short drive a trip overlaps, to the airport and back', () => {
    const trip = { end: '2026-09-16', id: 'yunnan', start: '2026-09-12', title: '云南' }
    const [outing] = findOutings([day('2026-09-12', 160)], [], [trip], [])

    expect(outing?.trip).toBe(trip)
  })

  test('tells an outing by its Moments’ cities and counts fast charges', () => {
    const days = [day('2026-07-25', 350), day('2026-07-26', 340)]
    const charges = [
      { startedAt: '2026-07-25T10:00:00+08:00', type: 'DC' as const },
      { startedAt: '2026-07-26T22:00:00+08:00', type: 'AC' as const },
    ]
    const moments = [
      { dateKey: '2026-07-24', locations: [{ city: '上海' }] },
      { dateKey: '2026-07-25', locations: [{ city: '杭州', sight: '西湖' }] },
      { dateKey: '2026-07-26', locations: [{ city: '杭州' }] },
      { dateKey: '2026-07-26', locations: [] },
    ]
    const [outing] = findOutings(days, charges, [], moments)

    expect(outing).toMatchObject({
      cities: ['杭州'],
      fastCharges: 1,
      momentCount: 3,
      sights: ['西湖'],
    })
  })
})

test('getDistanceLevel', () => {
  expect([0, 10, 100, 200, 900].map((km) => getDistanceLevel(km * 1000))).toEqual([0, 1, 2, 3, 4])
})

test('getYearWeeks starts on the Monday before New Year and fills the span with parked days', () => {
  const weeks = getYearWeeks(2026, new Map([['2026-01-02', 5000]]), {
    end: '2026-01-03',
    start: '2026-01-01',
  })

  // 2026 begins on a Thursday.
  expect(weeks[0]!.slice(0, 3)).toEqual([null, null, null])
  expect(weeks[0]!.slice(3, 7)).toEqual([
    { date: '2026-01-01', distanceMeters: 0 },
    { date: '2026-01-02', distanceMeters: 5000 },
    { date: '2026-01-03', distanceMeters: 0 },
    { date: '2026-01-04' },
  ])
  expect(weeks.flat().filter(Boolean)).toHaveLength(365)
})
