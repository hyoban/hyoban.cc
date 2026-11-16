import { expect, test } from 'vite-plus/test'
import type { MapCheckin, Moment, MomentTrip, PublicBill } from './index.ts'
import { getTripChat } from './trip-chat.ts'

// Made-up records: only what the conversation reads of them.
const trip = { start: '2026-03-07', end: '2026-03-08' } as MomentTrip

function at(occurredAt: string) {
  return { dateKey: occurredAt.slice(0, 10), occurredAt, occurredLabel: occurredAt.slice(11, 16) }
}

const moment = (id: string, time: string) =>
  ({ id, ...at(time), media: [], text: id }) as unknown as Moment
const checkin = (id: string, time: string) =>
  ({ id, ...at(time), venue: id }) as unknown as MapCheckin
const bill = (id: string, time: string) =>
  ({ id, ...at(time), kind: 'expense', amount: 1, currency: 'CNY' }) as unknown as PublicBill

test("a trip's days open with their date and read as a conversation", () => {
  const days = getTripChat(
    trip,
    [moment('lunch', '2026-03-07T12:00:00+08:00'), moment('view', '2026-03-08T09:00:00+08:00')],
    [checkin('station', '2026-03-07T12:30:00+08:00'), checkin('home', '2026-03-09T10:00:00+08:00')],
    [
      bill('meal', '2026-03-07T12:00:00+08:00'),
      bill('toll', '2026-03-07T15:00:00+08:00'),
      bill('parking', '2026-03-07T16:00:00+08:00'),
      bill('ticket', '2026-03-08T08:00:00+08:00'),
    ],
  )

  expect(days.map((day) => day.label)).toEqual(['第 1 天 3月7日 周六', '第 2 天 3月8日 周日'])
  expect(
    days.map((day) =>
      day.bubbles.map((bubble) =>
        bubble.kind === 'bills'
          ? `bills ${bubble.bills.map((item) => item.id).join(' ')}`
          : [
              bubble.kind === 'moment' ? bubble.moment.id : bubble.checkin.id,
              ...bubble.bills.map((item) => item.id),
              bubble.joinedAbove && '↑',
              bubble.joinedBelow && '↓',
            ]
              .filter(Boolean)
              .join(' '),
      ),
    ),
  ).toEqual([
    // The meal paid at lunch's very time goes in its bubble; the run of loose bills in one.
    ['lunch meal ↓', 'station ↑', 'bills toll parking'],
    ['bills ticket', 'view'],
  ])
})
