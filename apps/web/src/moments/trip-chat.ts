import { isInTrip } from '@hyoban/trip-map'
import type { VehicleCharge } from '@hyoban/data-spec'
import type { CarDay, MapCheckin, Moment, MomentTrip, PublicBill } from '@/moments'

// A trip's sidebar as the app's trip is: a conversation, each day opening with its date in a
// capsule, its Moments and check-ins bubbles from the right with what was paid and charged at
// their very time at their foot, and every run of the other bills and charges between them one
// bubble from the left, the day ending with what the car drove. A charge and the bill paid for
// it at its very time are one line, wherever that bill would go.

/** A charge with the bill paid for it, when the ledger has one at its very time. */
export type ChatCharge = { charge: VehicleCharge; bill?: PublicBill }

/** One line of a bubble from the left. */
export type ChatLine =
  | { kind: 'bill'; bill: PublicBill }
  | { kind: 'charge'; charge: ChatCharge }
  | { kind: 'drive'; day: CarDay }

export type ChatBubble =
  | {
      kind: 'moment'
      moment: Moment
      bills: PublicBill[]
      charges: ChatCharge[]
      joinedAbove: boolean
      joinedBelow: boolean
    }
  | {
      kind: 'checkin'
      checkin: MapCheckin
      bills: PublicBill[]
      charges: ChatCharge[]
      joinedAbove: boolean
      joinedBelow: boolean
    }
  | { kind: 'records'; lines: ChatLine[] }

export type ChatDay = {
  dateKey: string
  /** 第 2 天 11月14日 周六 */
  label: string
  bubbles: ChatBubble[]
}

type Owner = Moment | MapCheckin
type Sent = Exclude<ChatBubble, { kind: 'records' }>
/** What goes in the day's order: a bubble from the right, or a line from the left by its time. */
type Timed = { at: string; dateKey: string; order?: number } & (
  | { owner: Owner }
  | { line: Exclude<ChatLine, { kind: 'drive' }> }
)

const weekdayFormatter = new Intl.DateTimeFormat('zh-CN', { timeZone: 'UTC', weekday: 'short' })
const DAY = 86_400_000

/** When something was to the minute, in epoch milliseconds; undefined for one known by its day alone. */
function instantOf(at: string) {
  return at.length > 10 ? Date.parse(at) : undefined
}

function dayLabel(start: string, dateKey: string) {
  const [, month, day] = dateKey.split('-').map((part) => Number.parseInt(part, 10))
  const date = new Date(`${dateKey}T00:00:00Z`)
  const index = Math.round((date.getTime() - Date.parse(`${start}T00:00:00Z`)) / DAY) + 1
  return `第 ${index} 天 ${month}月${day}日 ${weekdayFormatter.format(date)}`
}

/**
 * The trip's [moments], public [checkins] and public [bills] in its span, with the car's
 * [charges] and the [days] it drove, as its days' conversation, oldest first. A bill goes to the
 * Moment, else the check-in, at its very time, as the app links one it wasn't given; the site's
 * bills name none. A charge takes the expense paid at its very time, and goes where that bill
 * would, else to the Moment or check-in at its own time, else among the day's records.
 */
export function getTripChat(
  trip: MomentTrip,
  moments: Moment[],
  checkins: MapCheckin[],
  bills: PublicBill[],
  charges: readonly VehicleCharge[] = [],
  days: readonly CarDay[] = [],
): ChatDay[] {
  const tripMoments = moments.filter((moment) => isInTrip(trip, moment.occurredAt))
  const tripCheckins = checkins.filter((checkin) => isInTrip(trip, checkin.occurredAt))
  const tripBills = bills.filter((bill) => isInTrip(trip, bill.occurredAt))
  const tripCharges = charges.filter((charge) => isInTrip(trip, charge.startedAt))
  const ownerAt = new Map<number, Owner>()

  for (const record of [...tripCheckins, ...tripMoments]) {
    const at = instantOf(record.occurredAt)
    if (at !== undefined) ownerAt.set(at, record)
  }

  // Each charge with the expense paid at its very time, that bill then shown with it alone.
  const tripChatCharges = pairCharges(tripCharges, tripBills)
  const paired = new Set(tripChatCharges.flatMap((charge) => (charge.bill ? [charge.bill] : [])))

  const linkedBills = new Map<Owner, PublicBill[]>()
  const linkedCharges = new Map<Owner, ChatCharge[]>()
  const timed: Timed[] = [
    ...tripMoments.map((moment) => ({
      at: moment.occurredAt,
      dateKey: moment.dateKey,
      owner: moment,
      ...(moment.order === undefined ? {} : { order: moment.order }),
    })),
    ...tripCheckins.map((checkin) => ({
      at: checkin.occurredAt,
      dateKey: checkin.dateKey,
      owner: checkin,
      ...(checkin.order === undefined ? {} : { order: checkin.order }),
    })),
  ]

  for (const bill of tripBills) {
    if (paired.has(bill)) continue
    const at = instantOf(bill.occurredAt)
    const owner = at === undefined ? undefined : ownerAt.get(at)
    if (owner) linkedBills.set(owner, [...(linkedBills.get(owner) ?? []), bill])
    else timed.push({ at: bill.occurredAt, dateKey: bill.dateKey, line: { kind: 'bill', bill } })
  }

  for (const charge of tripChatCharges) {
    const owner = ownerAt.get(Date.parse(charge.bill?.occurredAt ?? charge.charge.startedAt))
    if (owner) linkedCharges.set(owner, [...(linkedCharges.get(owner) ?? []), charge])
    else
      timed.push({
        at: charge.charge.startedAt,
        dateKey: charge.charge.startedAt.slice(0, 10),
        line: { kind: 'charge', charge },
      })
  }

  // By day, then by time, a record known by its day alone first, then as the records order themselves.
  timed.sort(
    (first, second) =>
      first.dateKey.localeCompare(second.dateKey) ||
      (instantOf(first.at) ?? 0) - (instantOf(second.at) ?? 0) ||
      (first.order ?? 0) - (second.order ?? 0),
  )
  const drives = new Map(
    days
      .filter((day) => day.date >= trip.start && day.date <= trip.end)
      .map((day) => [day.date, day]),
  )
  const dateKeys = [...new Set([...timed.map((item) => item.dateKey), ...drives.keys()])].sort()
  const result: ChatDay[] = []

  for (const dateKey of dateKeys) {
    const day: ChatDay = { dateKey, label: dayLabel(trip.start, dateKey), bubbles: [] }
    const addLine = (line: ChatLine) => {
      const last = day.bubbles.at(-1)
      if (last?.kind === 'records') last.lines.push(line)
      else day.bubbles.push({ kind: 'records', lines: [line] })
    }

    for (const item of timed.filter((entry) => entry.dateKey === dateKey)) {
      if ('line' in item) {
        addLine(item.line)
        continue
      }

      const owner = item.owner
      const last = day.bubbles.at(-1)
      const bubble: Sent =
        'venue' in owner
          ? {
              kind: 'checkin',
              checkin: owner,
              bills: linkedBills.get(owner) ?? [],
              charges: linkedCharges.get(owner) ?? [],
              joinedAbove: false,
              joinedBelow: false,
            }
          : {
              kind: 'moment',
              moment: owner,
              bills: linkedBills.get(owner) ?? [],
              charges: linkedCharges.get(owner) ?? [],
              joinedAbove: false,
              joinedBelow: false,
            }

      // A run of bubbles from the right is drawn together, only the last with a tail.
      if (last && last.kind !== 'records') {
        last.joinedBelow = true
        bubble.joinedAbove = true
      }

      day.bubbles.push(bubble)
    }

    // What the car drove ends the day.
    const drive = drives.get(dateKey)
    if (drive) addLine({ kind: 'drive', day: drive })

    result.push(day)
  }

  return result
}

/**
 * Each of [charges] with the expense among [bills] paid at its very time, the one line they make
 * wherever they are shown; a bill is taken once.
 */
export function pairCharges(
  charges: readonly VehicleCharge[],
  bills: readonly PublicBill[],
): ChatCharge[] {
  const expensesAt = new Map<number, PublicBill[]>()

  for (const bill of bills) {
    const at = instantOf(bill.occurredAt)
    if (bill.kind === 'expense' && at !== undefined)
      expensesAt.set(at, [...(expensesAt.get(at) ?? []), bill])
  }

  return charges.map((charge) => {
    const bill = expensesAt.get(Date.parse(charge.startedAt))?.shift()
    return bill ? { charge, bill } : { charge }
  })
}
