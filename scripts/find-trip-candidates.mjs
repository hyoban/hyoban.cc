import { parseArgs } from 'node:util'
import { fileURLToPath } from 'node:url'

import { locations } from '../src/data/locations.ts'
import { trips } from '../src/data/trips.ts'
import { getLocation } from '../src/moments/map-locations.ts'
import { getDayOffset, getDistanceKm } from '../src/moments/trips.ts'
import { readTripMoments } from './lib/trip-moments.mjs'

// Lists runs of days with Moments far from home that no trip in src/data/trips.ts
// covers yet, as candidates to add there. It only reports; nothing is written.
//
//   pnpm trips:candidates [--home wuwei] [--radius 100] [--min-days 2]

const { values } = parseArgs({
  options: {
    home: { default: 'wuwei', type: 'string' },
    'min-days': { default: '2', type: 'string' },
    radius: { default: '100', type: 'string' },
  },
})
const home = getLocation(locations, values.home)
const radiusKm = Number(values.radius)
const minDays = Number(values['min-days'])
const root = fileURLToPath(new URL('../', import.meta.url))
// One quiet day inside a run doesn't end it.
const MAX_GAP_DAYS = 2

const awayDays = new Map()

for (const { dateKey, id, locations: places } of await readTripMoments(root)) {
  const far = places.filter((location) => getDistanceKm(home, location) > radiusKm)

  if (far.length > 0) {
    const day = awayDays.get(dateKey) ?? { moments: [], places: new Set() }
    day.moments.push(id)

    for (const location of far) {
      day.places.add(location.name)
    }

    awayDays.set(dateKey, day)
  }
}

const runs = []

for (const dateKey of [...awayDays.keys()].sort((first, second) => first.localeCompare(second))) {
  const run = runs.at(-1)

  if (run && getDayOffset(run.end, dateKey) <= MAX_GAP_DAYS) {
    run.end = dateKey
    run.days.push(dateKey)
  } else {
    runs.push({ days: [dateKey], end: dateKey, start: dateKey })
  }
}

const candidates = runs.filter(
  (run) =>
    getDayOffset(run.start, run.end) + 1 >= minDays &&
    !trips.some((trip) => trip.start <= run.end && trip.end >= run.start),
)

if (candidates.length === 0) {
  console.log(`No uncovered runs of ${minDays}+ days more than ${radiusKm} km from ${home.name}.`)
}

for (const run of candidates) {
  const places = new Set(run.days.flatMap((dateKey) => [...awayDays.get(dateKey).places]))
  const momentCount = run.days.reduce(
    (count, dateKey) => count + awayDays.get(dateKey).moments.length,
    0,
  )

  console.log(
    `${run.start} – ${run.end}  ${getDayOffset(run.start, run.end) + 1} 天 · ${momentCount} 条记录\n  ${[...places].join('、')}`,
  )
}
