import type { VehicleCharge, VehicleExpense } from '@hyoban/data-spec'
import { describe, expect, test } from 'vite-plus/test'
import {
  TRIP_CATEGORIES,
  getChargePrice,
  getCostBreakdown,
  getExpenseFen,
  getExpensesBetween,
  pairChargePayments,
  readVehicleExpenses,
} from './vehicle-costs.ts'

const line = (
  at: string,
  amount: number,
  category: VehicleExpense['category'],
  extra: Partial<VehicleExpense> = {},
): VehicleExpense => ({ amount, at, category, ...extra })

const fastCharge = (at: string, amount: number) =>
  line(at, amount, 'charging', { subcategory: 'fast-charging' })

const charge = (startedAt: string, energyWh: number, minutes = 30): VehicleCharge => ({
  durationSeconds: minutes * 60,
  energyWh,
  source: { captureId: '0'.repeat(64), method: 'screen', pointer: '/' },
  startedAt,
  type: 'DC',
})

test('the ledger starts at the first payment for the car', () => {
  const expenses = readVehicleExpenses()

  expect(expenses[0]?.category).toBe('purchase')
  expect(expenses.every((expense) => expense.at >= expenses[0]!.at)).toBe(true)
})

describe('getExpenseFen', () => {
  const insurance = line('2026-07-19T19:03+08:00', 5239.42, 'insurance', {
    refunds: [
      { amount: 400, at: '2026-07-20T11:16+08:00' },
      { amount: 275, at: '2026-07-24T09:47+08:00' },
    ],
  })

  test('takes off what came back, in exact fen', () => {
    expect(getExpenseFen(insurance)).toBe(456_442)
  })

  test('takes off only what had come back by a day', () => {
    expect(getExpenseFen(insurance, '2026-07-19')).toBe(523_942)
    expect(getExpenseFen(insurance, '2026-07-20')).toBe(483_942)
  })
})

test('breaks costs down by category, leaving out what came back in full', () => {
  const costs = getCostBreakdown(
    [
      line('2026-01-01T10:00+08:00', 0.1, 'toll'),
      line('2026-01-01T11:00+08:00', 0.2, 'toll'),
      line('2026-01-01T12:00+08:00', 20, 'parking'),
      line('2026-01-01T13:00+08:00', 288, 'accessories', {
        refunds: [{ amount: 288, at: '2026-01-04T18:38+08:00' }],
      }),
      line('2026-01-01T14:00+08:00', 99, 'fine'),
    ],
    [...TRIP_CATEGORIES, 'accessories'],
  )

  expect(costs).toEqual({
    categories: [
      { category: 'parking', fen: 2000 },
      { category: 'toll', fen: 30 },
    ],
    total: 2030,
  })
})

test('picks the lines of a stretch of days, both ends included', () => {
  const expenses = [
    line('2026-05-01T00:10+08:00', 1, 'toll'),
    line('2026-05-03T23:59+08:00', 2, 'toll'),
    line('2026-05-04T00:00+08:00', 3, 'toll'),
  ]

  expect(getExpensesBetween(expenses, '2026-05-01', '2026-05-03')).toHaveLength(2)
})

describe('pairChargePayments', () => {
  test('pairs a fast charge with one payment around it, never a slow one', () => {
    const pairs = pairChargePayments(
      [
        charge('2026-06-19T10:00+08:00', 40_000),
        charge('2026-06-19T15:00+08:00', 10_000),
        { ...charge('2026-06-19T20:00+08:00', 30_000), type: 'AC' },
      ],
      [
        fastCharge('2026-06-19T10:40+08:00', 36),
        line('2026-06-19T20:30+08:00', 20, 'charging', { subcategory: 'home-charging' }),
        fastCharge('2026-06-20T10:40+08:00', 15),
      ],
    )

    expect(pairs).toHaveLength(1)
    expect(getChargePrice(pairs)).toEqual({
      count: 1,
      energyWh: 40_000,
      fen: 3600,
      yuanPerKwh: 0.9,
    })
  })

  test('leaves a session paid some other way without the next one’s payment', () => {
    // Paid with points at 09:42, so its window reaches the 12:56 payment for the next one.
    const morning = charge('2026-06-19T09:42+08:00', 31_100, 17)
    const noon = charge('2026-06-19T12:07+08:00', 23_800, 42)
    const pairs = pairChargePayments([morning, noon], [fastCharge('2026-06-19T12:56+08:00', 25.13)])

    expect(pairs.map(({ charge }) => charge)).toEqual([noon])
  })

  test('returns the pairs oldest first', () => {
    const first = charge('2026-06-19T09:00+08:00', 10_000)
    const second = charge('2026-06-19T09:20+08:00', 10_000)
    const pairs = pairChargePayments(
      [first, second],
      [fastCharge('2026-06-19T09:31+08:00', 9), fastCharge('2026-06-19T09:51+08:00', 9)],
    )

    expect(pairs.map(({ charge }) => charge)).toEqual([first, second])
  })

  test('has no price without energy', () => {
    expect(getChargePrice([]).yuanPerKwh).toBeUndefined()
  })
})
