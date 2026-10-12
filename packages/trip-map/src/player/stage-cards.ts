import type { Messages } from '../messages.ts'
import { createCountUp, createElement } from './dom.ts'
import { getTripMeta } from './intro.ts'
import { getSummaryStats } from './labels.ts'
import type { TripData, TripImage } from './types.ts'

// The story layout's centred cards for presenting and recording. A stop's photo card
// is built by the player, since it changes with each photo, and the heading above it
// is the day heading.

export function createIntroCard(trip: TripData, covers: readonly TripImage[], messages: Messages) {
  const card = createElement('div', 'trip-map-stage-card', '')
  const coverRow = createElement('div', 'trip-map-stage-card__covers', '')

  for (const cover of covers.slice(0, 3)) {
    const image = document.createElement('img')
    image.src = cover.thumbnail
    image.alt = ''
    coverRow.append(image)
  }

  card.append(
    createElement('p', 'trip-map-stage-card__title', trip.title),
    createElement('p', 'trip-map-stage-card__meta', getTripMeta(trip, messages)),
    coverRow,
  )
  return card
}

/** `siteLine` tells a viewer of the video where to find the trips, like `hyoban.cc/trip/`. */
export function createSummaryCard(trip: TripData, siteLine: string, messages: Messages) {
  const card = createElement('div', 'trip-map-stage-card', '')
  const stats = document.createElement('dl')

  stats.className = 'trip-map-stage-card__stats'

  for (const [value, label] of getSummaryStats(trip, messages)) {
    const item = document.createElement('div')
    const number = createElement('dd', '', '')

    number.append(createCountUp(value))
    item.append(createElement('dt', '', label), number)
    stats.append(item)
  }

  card.append(
    createElement('p', 'trip-map-stage-card__title', trip.title),
    stats,
    // Where a viewer of the video can find the trip.
    createElement('p', 'trip-map-stage-card__site', siteLine),
  )
  return card
}
