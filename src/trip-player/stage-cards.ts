import { SITE_URL } from '../consts'
import { createCountUp, createElement, createWeather } from './dom'
import { getTripMeta } from './intro'
import { getSummaryStats } from './labels'
import type { Slide, TripData, TripImage } from './types'

// The story layout used for presenting and recording: its centred cards and the day
// and time heading above a stop. A stop's photo card is built by the player, since it
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

export function createDayCard(trip: TripData, slide: Extract<Slide, { type: 'day' }>) {
  const card = createElement('div', 'calendar-map-trip-stage-card', '')
  const weather = trip.weather[slide.day]

  card.append(
    createElement('p', 'calendar-map-trip-stage-card__eyebrow', `第 ${slide.day} 天`),
    createElement('p', 'calendar-map-trip-stage-card__title', slide.date),
    ...(weather ? [createWeather(weather, 'calendar-map-trip-stage-card__weather')] : []),
    createElement('p', 'calendar-map-trip-stage-card__meta', slide.places.join(' → ')),
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
 * The story layout's day, date and time. Their numbers roll from one stop's to the
 * next (number-flow) instead of being swapped, so moving on reads as time passing.
 */
export function createStageHeading(eyebrow: HTMLElement, title: HTMLElement) {
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
  const time = document.createElement('span')
  const weekday = document.createElement('span')

  // The day and time lead; the date sits small above them.
  time.append(' · ', hour, ':', minute)
  title.replaceChildren('第 ', day, ' 天', time)
  eyebrow.replaceChildren(month, '月', date, '日', weekday)

  return {
    show(stop: { date: string; day: number; time?: string | undefined }) {
      // Dates read like 10月2日周五 and times like 19:43; anything else is shown as is.
      const dateParts = /^(\d+)月(\d+)日(.*)$/.exec(stop.date)
      const timeParts = /^(\d{1,2}):(\d{2})$/.exec(stop.time ?? '')

      day.update(stop.day)
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

        // Back from a date shown as plain text; otherwise the numbers stay in place.
        if (!month.isConnected) {
          eyebrow.replaceChildren(month, '月', date, '日', weekday)
        }
      } else {
        eyebrow.textContent = stop.date
      }
    },
  }
}
