import { SITE_URL } from '../consts'
import { createCountUp, createElement } from './dom'
import { getTripMeta } from './intro'
import { getSummaryStats } from './labels'
import type { ClockMoment } from './slides'
import type { TripData, TripImage } from './types'

// The story layout used for presenting and recording: its centred cards and the day
// and time heading above a stop, which the side card shares. A stop's photo card is built by the player, since it
// changes with each photo.

export function createIntroCard(trip: TripData, covers: readonly TripImage[]) {
  const card = createElement('div', 'calendar-map-trip-stage-card', '')
  const coverRow = createElement('div', 'calendar-map-trip-stage-card__covers', '')

  for (const cover of covers.slice(0, 3)) {
    const image = document.createElement('img')
    image.src = cover.thumbnail
    image.alt = ''
    coverRow.append(image)
  }

  card.append(
    createElement('p', 'calendar-map-trip-stage-card__title', trip.title),
    createElement('p', 'calendar-map-trip-stage-card__meta', getTripMeta(trip)),
    coverRow,
  )
  return card
}

export function createSummaryCard(trip: TripData) {
  const card = createElement('div', 'calendar-map-trip-stage-card', '')
  const stats = document.createElement('dl')

  stats.className = 'calendar-map-trip-stage-card__stats'

  for (const [value, label] of getSummaryStats(trip)) {
    const item = document.createElement('div')
    const number = createElement('dd', '', '')

    number.append(createCountUp(value))
    item.append(createElement('dt', '', label), number)
    stats.append(item)
  }

  card.append(
    createElement('p', 'calendar-map-trip-stage-card__title', trip.title),
    stats,
    // Where a viewer of the video can find the trip.
    createElement('p', 'calendar-map-trip-stage-card__site', `${new URL(SITE_URL).host}/trip/`),
  )
  return card
}

/**
 * A stop's day, date and time, over the side card or the story layout. Their numbers
 * roll from one stop's to the next (number-flow), and tick on with the route's tip
 * in between, so moving on reads as time passing.
 */
export function createDayHeading(eyebrow: HTMLElement, title: HTMLElement) {
  const roll = (minimumIntegerDigits = 1) => {
    const flow = document.createElement('number-flow')
    flow.format = { minimumIntegerDigits, useGrouping: false }
    return flow
  }
  const day = roll()
  const hour = roll(2)
  const minute = roll(2)
  const month = roll()
  const date = roll()
  const flows = [day, hour, minute, month, date]
  const time = document.createElement('span')
  const weekday = document.createElement('span')
  // A date that isn't like 10月2日周五 is shown as it is.
  let plainDate: string | undefined
  let shown: string | undefined

  time.append(' · ', hour, ':', minute)

  function renderDate() {
    if (plainDate === undefined) {
      eyebrow.replaceChildren(month, '月', date, '日', weekday)
    } else {
      eyebrow.textContent = plainDate
    }
  }

  return {
    /** Puts the heading back in place of whatever text the card showed meanwhile. */
    attach() {
      // The day and time lead; the date sits small above them.
      title.replaceChildren('第 ', day, ' 天', time)
      renderDate()
    },
    /** Rolls to `moment`, or just sets it while the clock ticks on with the route. */
    set(moment: ClockMoment, options: { animate: boolean }) {
      const key = `${moment.day} ${moment.date} ${moment.time}`

      if (key === shown) {
        return
      }

      const dateParts = /^(\d+)月(\d+)日(.*)$/.exec(moment.date)
      const timeParts = /^(\d{1,2}):(\d{2})$/.exec(moment.time ?? '')
      const attached = day.isConnected

      shown = key

      for (const flow of flows) {
        flow.animated = options.animate
      }

      day.update(moment.day)
      time.hidden = !timeParts

      // A stop without a time hides it but leaves the last one, to roll on from.
      if (timeParts) {
        hour.update(Number(timeParts[1]))
        minute.update(Number(timeParts[2]))
      }

      if (dateParts) {
        month.update(Number(dateParts[1]))
        date.update(Number(dateParts[2]))
        weekday.textContent = dateParts[3] ?? ''
      }

      const nextPlainDate = dateParts ? undefined : moment.date

      if (nextPlainDate !== plainDate) {
        plainDate = nextPlainDate

        if (attached) {
          renderDate()
        }
      }
    },
  }
}
