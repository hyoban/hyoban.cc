import { SITE_URL } from '../consts'
import { createCountUp, createElement } from './dom'
import { getTripMeta } from './intro'
import { getSummaryStats } from './labels'
import type { TripData, TripImage } from './types'

// The story layout's centred cards for presenting and recording. A stop's photo card
// is built by the player, since it changes with each photo, and the heading above it
// is the day heading.

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
