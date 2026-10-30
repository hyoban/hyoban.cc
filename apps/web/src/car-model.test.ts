import { describe, expect, test } from 'vite-plus/test'
import { getCarDays } from './car-model.ts'

const level = (meters: number) => (meters === 0 ? 0 : meters < 50_000 ? 1 : 4)

describe('getCarDays', () => {
  test('keeps every day between the first and the last, summing each', () => {
    expect(
      getCarDays(
        [
          { date: '2026-01-02', meters: 0 },
          { date: '2026-01-01', fen: 500 },
          { date: '2026-01-04', meters: 120_000 },
          { date: '2026-01-04', wh: 30_000 },
          { date: '2026-01-04', wh: 10_000, fen: 200 },
        ],
        level,
      ),
    ).toEqual({
      start: '2026-01-01',
      days: [
        [0, 0, 500],
        [0, 0, 0],
        [0, 0, 0],
        [120_000, 40_000, 200],
      ],
      levels: '.0.4',
    })
  })

  test('crosses months and leaves out a year without records', () => {
    expect(getCarDays([], level)).toBeUndefined()
    expect(
      getCarDays(
        [
          { date: '2025-12-31', meters: 10_000 },
          { date: '2026-01-01', meters: 60_000 },
        ],
        level,
      )?.levels,
    ).toBe('14')
  })
})
