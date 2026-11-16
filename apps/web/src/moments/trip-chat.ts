import { compareMoments, isInTrip } from '@hyoban/trip-map'
import type { MapCheckin, Moment, MomentTrip, PublicBill } from '@/moments'

// A trip's sidebar as the app's trip is: a conversation, each day opening with its date in a
// capsule, its Moments and check-ins bubbles from the right with the bills paid at their very
// time at their foot, and every run of the other bills between them one bubble from the left.

export type ChatBubble =
  | {
      kind: 'moment'
      moment: Moment
      bills: PublicBill[]
      joinedAbove: boolean
      joinedBelow: boolean
    }
  | {
      kind: 'checkin'
      checkin: MapCheckin
      bills: PublicBill[]
      joinedAbove: boolean
      joinedBelow: boolean
    }
  | { kind: 'bills'; bills: PublicBill[] }

export type ChatDay = {
  dateKey: string
  /** 第 2 天 11月14日 周六 */
  label: string
  bubbles: ChatBubble[]
}

type Record = Moment | MapCheckin | PublicBill
type Sent = Exclude<ChatBubble, { kind: 'bills' }>

const weekdayFormatter = new Intl.DateTimeFormat('zh-CN', { timeZone: 'UTC', weekday: 'short' })
const DAY = 86_400_000

/** When a record was to the minute, in epoch milliseconds; undefined for one known by its day alone. */
function instantOf(record: Pick<Record, 'occurredAt'>) {
  return record.occurredAt.length > 10 ? Date.parse(record.occurredAt) : undefined
}

function dayLabel(start: string, dateKey: string) {
  const [, month, day] = dateKey.split('-').map((part) => Number.parseInt(part, 10))
  const date = new Date(`${dateKey}T00:00:00Z`)
  const index = Math.round((date.getTime() - Date.parse(`${start}T00:00:00Z`)) / DAY) + 1
  return `第 ${index} 天 ${month}月${day}日 ${weekdayFormatter.format(date)}`
}

/**
 * The trip's [moments], public [checkins] and public [bills] in its span as its days'
 * conversation, oldest first. A bill goes to the Moment, else the check-in, at its very
 * time, as the app links one it wasn't given; the site's bills name none.
 */
export function getTripChat(
  trip: MomentTrip,
  moments: Moment[],
  checkins: MapCheckin[],
  bills: PublicBill[],
): ChatDay[] {
  const tripMoments = moments.filter((moment) => isInTrip(trip, moment.occurredAt))
  const tripCheckins = checkins.filter((checkin) => isInTrip(trip, checkin.occurredAt))
  const tripBills = bills.filter((bill) => isInTrip(trip, bill.occurredAt))
  const byTime = new Map<number, Moment | MapCheckin>()

  for (const record of [...tripCheckins, ...tripMoments]) {
    const at = instantOf(record)
    if (at !== undefined) byTime.set(at, record)
  }

  const linked = new Map<Moment | MapCheckin, PublicBill[]>()
  const loose: PublicBill[] = []

  for (const bill of tripBills) {
    const at = instantOf(bill)
    const owner = at === undefined ? undefined : byTime.get(at)
    if (owner) linked.set(owner, [...(linked.get(owner) ?? []), bill])
    else loose.push(bill)
  }

  const records: Record[] = [...tripMoments, ...tripCheckins, ...loose].sort(compareMoments)
  const days: ChatDay[] = []

  for (const record of records) {
    let day = days.at(-1)

    if (day?.dateKey !== record.dateKey) {
      day = { dateKey: record.dateKey, label: dayLabel(trip.start, record.dateKey), bubbles: [] }
      days.push(day)
    }

    const last = day.bubbles.at(-1)

    if ('kind' in record) {
      if (last?.kind === 'bills') last.bills.push(record)
      else day.bubbles.push({ kind: 'bills', bills: [record] })
      continue
    }

    const bubble: Sent =
      'venue' in record
        ? {
            kind: 'checkin',
            checkin: record,
            bills: linked.get(record) ?? [],
            joinedAbove: false,
            joinedBelow: false,
          }
        : {
            kind: 'moment',
            moment: record,
            bills: linked.get(record) ?? [],
            joinedAbove: false,
            joinedBelow: false,
          }

    // A run of bubbles from the right is drawn together, only the last with a tail.
    if (last && last.kind !== 'bills') {
      last.joinedBelow = true
      bubble.joinedAbove = true
    }

    day.bubbles.push(bubble)
  }

  return days
}
