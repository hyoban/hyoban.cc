import type { Device, Devices } from '@hyoban/data-spec'

// The use page's view of data/devices.json: each device as a span from the day it was
// bought to the day it was sold (or today), packed into one lane per category, and the
// same spans as a feed of buy and sell events for narrow screens.

/** `backup` is a device still in use while a newer one of its category is the main one. */
export type DeviceStatus = 'in-use' | 'backup' | 'idle' | 'sold'

export interface DevicePeriod {
  start: string
  end: string
}

export interface DeviceSpan {
  id: string
  device: Device
  status: DeviceStatus
  start: string
  end: string
  /** Days owned, at least one. */
  days: number
  /** What owning it cost per day: the price less what it sold for, over the days owned. */
  dailyCost: number
  /** When it was a backup: owned and used while a newer one of its category was. */
  backup: DevicePeriod[]
}

export interface DeviceLane {
  category: string
  /** Spans that don't overlap share a row. */
  rows: DeviceSpan[][]
}

export interface DeviceEvent {
  date: string
  kind: 'bought' | 'sold'
  span: DeviceSpan
}

const DAY = 86_400_000

/** A YYYY-MM-DD date as a count of days, for differences. */
export function getDayNumber(date: string) {
  return Date.parse(`${date}T00:00:00Z`) / DAY
}

/** Every device as a span, oldest purchase first; `today` ends the ones still owned. */
export function getDeviceSpans(devices: Devices, today: string): DeviceSpan[] {
  const spans = Object.entries(devices)
    .map(([id, device]): DeviceSpan => {
      const start = device.bought.on
      const end = device.sold?.on ?? today
      const days = Math.max(1, getDayNumber(end) - getDayNumber(start))

      return {
        backup: [],
        days,
        dailyCost: (device.bought.price - (device.sold?.price ?? 0)) / days,
        device,
        end,
        id,
        start,
        status: device.sold ? 'sold' : device.idle ? 'idle' : 'in-use',
      }
    })
    .sort((first, second) => first.start.localeCompare(second.start))

  markBackups(spans)
  return spans
}

/**
 * Within a category, the newest device in use is the main one and the others in use at
 * the time are backups. Idle devices aren't in use, so they neither count nor are counted.
 */
function markBackups(spans: DeviceSpan[]) {
  const used = spans.filter((span) => !span.device.idle)
  const categories = Map.groupBy(used, (span) => span.device.category)

  for (const group of categories.values()) {
    const dates = [...new Set(group.flatMap((span) => [span.start, span.end]))].sort()

    for (const [index, start] of dates.slice(0, -1).entries()) {
      const end = dates[index + 1]!
      const active = group.filter((span) => span.start <= start && end <= span.end)

      for (const span of active.slice(0, -1)) {
        const last = span.backup.at(-1)

        if (last?.end === start) {
          last.end = end
        } else {
          span.backup.push({ end, start })
        }
      }
    }
  }

  for (const span of used) {
    if (span.status === 'in-use' && span.backup.at(-1)?.end === span.end) {
      span.status = 'backup'
    }
  }
}

/**
 * One lane per category, in the order each category was first bought. A span takes the
 * first row it fits in after the row's last span; `getExtent` says what a span takes up,
 * its days by default, or more when its label sits outside its bar.
 */
export function getDeviceLanes(
  spans: DeviceSpan[],
  getExtent: (span: DeviceSpan) => readonly [number, number] = (span) => [
    getDayNumber(span.start),
    getDayNumber(span.end),
  ],
): DeviceLane[] {
  const lanes = new Map<string, { ends: number[]; rows: DeviceSpan[][] }>()

  for (const span of spans) {
    const lane = lanes.get(span.device.category) ?? { ends: [], rows: [] }
    const [from, to] = getExtent(span)
    const index = lane.ends.findIndex((end) => end < from)

    if (index === -1) {
      lane.rows.push([span])
      lane.ends.push(to)
    } else {
      lane.rows[index]!.push(span)
      lane.ends[index] = to
    }

    lanes.set(span.device.category, lane)
  }

  return [...lanes].map(([category, { rows }]) => ({ category, rows }))
}

/** Every purchase and sale, newest first. */
export function getDeviceEvents(spans: DeviceSpan[]): DeviceEvent[] {
  return spans
    .flatMap((span) => [
      { date: span.start, kind: 'bought' as const, span },
      ...(span.device.sold ? [{ date: span.device.sold.on, kind: 'sold' as const, span }] : []),
    ])
    .sort((first, second) => second.date.localeCompare(first.date))
}
