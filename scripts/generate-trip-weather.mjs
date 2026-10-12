import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { trips } from '../src/data/trips.ts'
import { buildTrip, getDayPlaces, getWeatherKey } from '@hyoban/trip-map'
import { readTripMoments, resolveLocation } from './lib/trip-moments.mjs'

// Looks up each trip day's weather where the day starts, from Open-Meteo's historical
// archive (free, no key), so the day cards can show it. Cached days are reused; days
// the archive hasn't caught up with yet (the last few days) are tried again next run.

const root = fileURLToPath(new URL('../', import.meta.url))
const weatherFile = join(root, 'src/data/trip-weather.json')
const moments = await readTripMoments(root)
const cached = JSON.parse(await readFile(weatherFile, 'utf8').catch(() => '{}'))
const weather = {}
let fetched = 0

for (const trip of trips) {
  for (const [dateKey, place] of getDayPlaces(buildTrip(trip, moments, { resolveLocation }))) {
    const key = getWeatherKey(dateKey, place)

    if (cached[key]) {
      weather[key] = cached[key]
      continue
    }

    const day = await fetchDay(dateKey, place)
    fetched += 1

    if (day) {
      weather[key] = day
      console.log(`${trip.id}: ${dateKey} ${place.name} ${day.min}–${day.max}°C (code ${day.code})`)
    } else {
      console.warn(`${trip.id}: no weather yet for ${dateKey} ${place.name}`)
    }
  }
}

const sorted = Object.fromEntries(
  Object.keys(weather)
    .sort((first, second) => first.localeCompare(second))
    .map((key) => [key, weather[key]]),
)

await writeFile(weatherFile, `${JSON.stringify(sorted, null, 2)}\n`)
console.log(`${Object.keys(weather).length} days (${fetched} fetched).`)

async function fetchDay(dateKey, place) {
  const url = new URL('https://archive-api.open-meteo.com/v1/archive')
  url.searchParams.set('latitude', String(place.latitude))
  url.searchParams.set('longitude', String(place.longitude))
  url.searchParams.set('start_date', dateKey)
  url.searchParams.set('end_date', dateKey)
  url.searchParams.set('daily', 'weather_code,temperature_2m_max,temperature_2m_min')
  url.searchParams.set('timezone', 'Asia/Shanghai')

  const response = await fetch(url)

  if (!response.ok) {
    throw new Error(`Weather request failed for ${dateKey} ${place.name}: ${response.status}`)
  }

  const { daily } = await response.json()
  const [code, max, min] = [
    daily.weather_code[0],
    daily.temperature_2m_max[0],
    daily.temperature_2m_min[0],
  ]

  return code === null || max === null || min === null
    ? undefined
    : { code, max: Math.round(max), min: Math.round(min) }
}
