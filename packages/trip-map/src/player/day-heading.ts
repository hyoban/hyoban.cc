import { getDateParts } from '../format.ts'
import type { Messages } from '../messages.ts'
import type { ClockMoment } from './slides.ts'

/**
 * A day, date and time: `date` gets the date (10月2日周五), `title` the day and time
 * (第 3 天\u200319:43). Their numbers roll (number-flow) from one moment to the next, or
 * just tick on while the clock runs with the route, so moving on reads as time passing.
 */
export function createDayHeading(date: HTMLElement, title: HTMLElement, messages: Messages) {
  const roll = (minimumIntegerDigits = 1) => {
    const flow = document.createElement('number-flow')
    flow.format = { minimumIntegerDigits, useGrouping: false }
    return flow
  }
  const day = roll()
  const hour = roll(2)
  const minute = roll(2)
  const month = roll()
  const monthDay = roll()
  const flows = [day, hour, minute, month, monthDay]
  const time = document.createElement('span')
  // The day's label around its number: 第 {day} 天.
  const [dayBefore = '', dayAfter = ''] = messages.dayLabel.split('{day}')
  // The date's pieces that aren't its numbers, to know when its shape changes.
  let dateShape: string | undefined
  let shown: string | undefined

  time.append(messages.separator, hour, ':', minute)
  title.replaceChildren(dayBefore, day, dayAfter, time)

  return {
    /** Rolls to `moment`, or with `animate` off just sets it, as a running clock does. */
    set(moment: ClockMoment, options: { animate: boolean }) {
      const key = `${moment.day} ${moment.dateKey} ${moment.time}`

      if (key === shown) {
        return
      }

      const timeParts = /^(\d{1,2}):(\d{2})$/.exec(moment.time ?? '')
      const parts = getDateParts(moment.dateKey, messages.locale)
      const shape = parts
        .map((part) => (part.type === 'day' || part.type === 'month' ? part.type : part.value))
        .join('\u0000')

      shown = key

      for (const flow of flows) {
        flow.animated = options.animate
      }

      day.update(moment.day)
      time.hidden = !timeParts

      // A moment without a time hides it but leaves the last one, to roll on from.
      if (timeParts) {
        hour.update(Number(timeParts[1]))
        minute.update(Number(timeParts[2]))
      }

      // The numbers roll in place; the words around them are laid out again only
      // when they change (a different weekday, or a date the locale writes otherwise).
      if (shape !== dateShape) {
        dateShape = shape
        date.replaceChildren(
          ...parts.map((part) =>
            part.type === 'month' ? month : part.type === 'day' ? monthDay : part.value,
          ),
        )
      }

      for (const part of parts) {
        if (part.type === 'month') {
          month.update(Number(part.value))
        } else if (part.type === 'day') {
          monthDay.update(Number(part.value))
        }
      }
    },
  }
}
