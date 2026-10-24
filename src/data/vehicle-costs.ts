import { vehicleExpensesSchema, type VehicleCharge, type VehicleExpense } from '@hyoban/data-spec'
import { dataRoot, readJson } from './store.ts'
import { VEHICLE_ID } from './vehicle.ts'

// The site's view of the car's ledger in data/zeekr/expenses: what each line finally
// cost, what a stretch of days cost by category, and what fast charging cost a kWh, by
// pairing each DC session with the payment made for it. Amounts are summed in fen, so
// yuan with two decimals add up exactly.

export type ExpenseCategory = VehicleExpense['category']

export const CATEGORY_LABELS: Record<ExpenseCategory, string> = {
  accessories: '车品',
  'car-wash': '洗车',
  charging: '充电',
  fine: '罚单',
  inspection: '车检',
  insurance: '保险',
  maintenance: '保养',
  parking: '停车',
  purchase: '购车',
  repair: '维修',
  toll: '过路费',
}

/** What it costs to drive the car, as against owning it: per kilometre, these. */
export const RUNNING_CATEGORIES: readonly ExpenseCategory[] = [
  'charging',
  'toll',
  'parking',
  'car-wash',
  'maintenance',
  'repair',
  'inspection',
]

/** What a trip spends on the road. */
export const TRIP_CATEGORIES: readonly ExpenseCategory[] = ['toll', 'charging', 'parking']

const ledgers = new Map<string, VehicleExpense[]>()

/** The ledger, from the car's first payment on, oldest first; read once per root. */
export function readVehicleExpenses(root = dataRoot): VehicleExpense[] {
  let entries = ledgers.get(root)
  if (!entries) {
    entries = vehicleExpensesSchema.parse(
      readJson(`zeekr/expenses/${VEHICLE_ID}.json`, root),
    ).entries
    ledgers.set(root, entries)
  }
  return entries
}

const toFen = (yuan: number) => Math.round(yuan * 100)

/**
 * What an expense cost in fen: what was paid less what came back for it, by the end of
 * `until` (`YYYY-MM-DD`) when given, else all that ever did.
 */
export function getExpenseFen(expense: VehicleExpense, until?: string) {
  const refunded = (expense.refunds ?? [])
    .filter((refund) => !until || refund.at.slice(0, 10) <= until)
    .reduce((total, refund) => total + toFen(refund.amount), 0)
  return toFen(expense.amount) - refunded
}

export interface CostBreakdown {
  /** In fen. */
  total: number
  /** Each category's cost in fen, most first; a category that cost nothing is left out. */
  categories: { category: ExpenseCategory; fen: number }[]
}

/**
 * The cost of the lines in `categories`, or of every line, by category; with `until`,
 * less only what had come back by then.
 */
export function getCostBreakdown(
  expenses: readonly VehicleExpense[],
  categories?: readonly ExpenseCategory[],
  until?: string,
): CostBreakdown {
  const byCategory = new Map<ExpenseCategory, number>()

  for (const expense of expenses) {
    if (categories && !categories.includes(expense.category)) continue
    const fen = getExpenseFen(expense, until)
    if (fen !== 0) byCategory.set(expense.category, (byCategory.get(expense.category) ?? 0) + fen)
  }

  const list = [...byCategory]
    .filter(([, fen]) => fen > 0)
    .map(([category, fen]) => ({ category, fen }))
    .sort((first, second) => second.fen - first.fen)

  return { categories: list, total: list.reduce((total, { fen }) => total + fen, 0) }
}

/** The lines on the days from `start` to `end`, both `YYYY-MM-DD` and inclusive. */
export function getExpensesBetween(
  expenses: readonly VehicleExpense[],
  start: string,
  end: string,
) {
  return expenses.filter((expense) => {
    const date = expense.at.slice(0, 10)
    return start <= date && date <= end
  })
}

/** A payment can come this long before a session starts, for a charger paid up front. */
const PAID_BEFORE_MS = 30 * 60_000
/** Or this long after it ends, when the app settles or it was noted down later. */
const PAID_AFTER_MS = 3 * 3_600_000

export interface ChargePayment {
  charge: VehicleCharge
  payment: VehicleExpense
}

/**
 * Each DC session with the fast-charging payment made for it, oldest first. A payment
 * counts between half an hour before a session started and three hours after it ended,
 * and the session and payment closest to the session's end pair first, so a session paid
 * with a charge card or points, which has no payment of its own, doesn't take the next
 * one's.
 */
export function pairChargePayments(
  charges: readonly VehicleCharge[],
  expenses: readonly VehicleExpense[],
): ChargePayment[] {
  const payments = expenses.filter((expense) => expense.subcategory === 'fast-charging')
  const candidates: (ChargePayment & { gap: number })[] = []

  for (const charge of charges) {
    if (charge.type !== 'DC') continue
    const start = Date.parse(charge.startedAt)
    const end = start + charge.durationSeconds * 1000

    for (const payment of payments) {
      const at = Date.parse(payment.at)
      if (at >= start - PAID_BEFORE_MS && at <= end + PAID_AFTER_MS) {
        candidates.push({ charge, gap: Math.abs(at - end), payment })
      }
    }
  }

  const paired = new Set<VehicleCharge>()
  const taken = new Set<VehicleExpense>()
  const pairs: ChargePayment[] = []

  for (const { charge, payment } of candidates.sort((first, second) => first.gap - second.gap)) {
    if (paired.has(charge) || taken.has(payment)) continue
    paired.add(charge)
    taken.add(payment)
    pairs.push({ charge, payment })
  }

  return pairs.sort((first, second) =>
    first.charge.startedAt.localeCompare(second.charge.startedAt),
  )
}

/** What the paired sessions took and cost together, and so the yuan a kWh. */
export function getChargePrice(pairs: readonly ChargePayment[]) {
  const energyWh = pairs.reduce((total, { charge }) => total + charge.energyWh, 0)
  const fen = pairs.reduce((total, { payment }) => total + getExpenseFen(payment), 0)
  return {
    count: pairs.length,
    energyWh,
    fen,
    yuanPerKwh: energyWh > 0 ? fen / 100 / (energyWh / 1000) : undefined,
  }
}

const yuanFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 })
const centFormatter = new Intl.NumberFormat('zh-CN', {
  maximumFractionDigits: 2,
  minimumFractionDigits: 2,
})

/** `¥1,102` for fen, rounded to the yuan. */
export function formatYuan(fen: number) {
  return `¥${yuanFormatter.format(fen / 100)}`
}

/** `¥0.58`, for a rate in yuan. */
export function formatRate(yuan: number) {
  return `¥${centFormatter.format(yuan)}`
}
