import { expect, test } from 'vite-plus/test'
import type { VehicleCharge } from '@hyoban/data-spec'
import type { CarDay, MapCheckin, Moment, MomentTrip, PublicBill } from './index.ts'
import { getTripChat, type ChatDay, type ChatLine } from './trip-chat.ts'

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
const charge = (startedAt: string) =>
  ({ startedAt, type: 'DC', energyWh: 30_000, durationSeconds: 900 }) as unknown as VehicleCharge

const lineName = (line: ChatLine) =>
  line.kind === 'bill'
    ? line.bill.id
    : line.kind === 'charge'
      ? `charge${line.charge.bill ? `+${line.charge.bill.id}` : ''}`
      : `drive ${line.day.distanceMeters / 1000}`

/** Each day's bubbles in a line each: a bubble from the right by its record and what is at its foot. */
const read = (days: ChatDay[]) =>
  days.map((day) =>
    day.bubbles.map((bubble) =>
      bubble.kind === 'records'
        ? `records ${bubble.lines.map(lineName).join(' ')}`
        : [
            bubble.kind === 'moment' ? bubble.moment.id : bubble.checkin.id,
            ...bubble.charges.map((item) => `charge${item.bill ? `+${item.bill.id}` : ''}`),
            ...bubble.bills.map((item) => item.id),
            bubble.joinedAbove && '↑',
            bubble.joinedBelow && '↓',
          ]
            .filter(Boolean)
            .join(' '),
    ),
  )

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
  expect(read(days)).toEqual([
    // The meal paid at lunch's very time goes in its bubble; the run of loose bills in one.
    ['lunch meal ↓', 'station ↑', 'records toll parking'],
    ['records ticket', 'view'],
  ])
})

test('a charge is one line with the bill paid at its time, and each day the car moved ends with its driving', () => {
  const day = (date: string, km: number): CarDay => ({
    date,
    distanceMeters: km * 1000,
    durationSeconds: 3600,
  })
  const days = getTripChat(
    trip,
    [moment('lunch', '2026-03-07T12:00:00+08:00')],
    [checkin('charger', '2026-03-07T10:48:00+08:00')],
    [
      bill('fast', '2026-03-07T10:48:00+08:00'),
      bill('slow', '2026-03-07T13:03:00+08:00'),
      bill('toll', '2026-03-07T15:00:00+08:00'),
    ],
    [
      // At the charger's check-in, with its bill; after lunch with its own bill; overnight, alone.
      charge('2026-03-07T10:48+08:00'),
      charge('2026-03-07T13:03+08:00'),
      charge('2026-03-07T22:00+08:00'),
    ],
    [day('2026-03-07', 148), day('2026-03-08', 30), day('2026-03-10', 99)],
  )

  expect(read(days)).toEqual([
    ['charger charge+fast ↓', 'lunch ↑', 'records charge+slow toll charge drive 148'],
    // A day with only the car's driving still has its line.
    ['records drive 30'],
  ])
})
