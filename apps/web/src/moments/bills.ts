import type { PublicBill } from '@/moments'

const formatters = new Map<string, Intl.NumberFormat>()

/** `¥86`, `¥12.5` or `JP¥1,200`: an amount in its currency's unit, as the ledger shows it. */
export function formatBillAmount(amount: number, currency: string) {
  let formatter = formatters.get(currency)

  if (!formatter) {
    formatter = new Intl.NumberFormat('zh-CN', {
      currency,
      currencyDisplay: 'symbol',
      minimumFractionDigits: 0,
      style: 'currency',
    })
    formatters.set(currency, formatter)
  }

  return formatter.format(amount)
}

/** What the bills spent, expenses alone, in each currency they were paid in, the one paid most often first. */
export function getSpending(bills: readonly PublicBill[]) {
  const totals = new Map<string, { amount: number; count: number }>()

  for (const bill of bills) {
    if (bill.kind !== 'expense') {
      continue
    }

    const total = totals.get(bill.currency) ?? { amount: 0, count: 0 }
    total.amount += bill.amount
    total.count += 1
    totals.set(bill.currency, total)
  }

  return [...totals]
    .map(([currency, { amount, count }]) => ({
      amount: Math.round(amount * 100) / 100,
      count,
      currency,
    }))
    .sort((first, second) => second.count - first.count)
}
