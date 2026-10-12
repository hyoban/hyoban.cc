import type { ClockMoment } from './slides.ts'

/**
 * A day, date and time: `date` gets the date (10月2日周五), `title` the day and time
 * (第 3 天 · 19:43). Their numbers roll (number-flow) from one moment to the next, or
 * just tick on while the clock runs with the route, so moving on reads as time passing.
 */
export function createDayHeading(date: HTMLElement, title: HTMLElement) {
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
  const weekday = document.createElement('span')
  const rolledDate = [month, '月', monthDay, '日', weekday]
  // A date that isn't like 10月2日周五 is shown as it is.
  let plainDate: string | undefined
  let shown: string | undefined

  time.append(' · ', hour, ':', minute)
  title.replaceChildren('第 ', day, ' 天', time)
  date.replaceChildren(...rolledDate)

  return {
    /** Rolls to `moment`, or with `animate` off just sets it, as a running clock does. */
    set(moment: ClockMoment, options: { animate: boolean }) {
      const key = `${moment.day} ${moment.date} ${moment.time}`

      if (key === shown) {
        return
      }

      const dateParts = /^(\d+)月(\d+)日(.*)$/.exec(moment.date)
      const timeParts = /^(\d{1,2}):(\d{2})$/.exec(moment.time ?? '')
      const nextPlainDate = dateParts ? undefined : moment.date

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

      if (dateParts) {
        month.update(Number(dateParts[1]))
        monthDay.update(Number(dateParts[2]))
        weekday.textContent = dateParts[3] ?? ''
      }

      if (nextPlainDate !== plainDate) {
        plainDate = nextPlainDate

        if (plainDate === undefined) {
          date.replaceChildren(...rolledDate)
        } else {
          date.textContent = plainDate
        }
      }
    },
  }
}
