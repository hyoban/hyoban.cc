import type { WeatherIcon } from './icons.ts'

// Weather for a trip's day cards, from the WMO codes Open-Meteo reports.

/** A day's weather as cached: its WMO code and the temperatures in °C. */
export type DayWeather = Readonly<{ code: number; max: number; min: number }>

export type WeatherDescription = { icon: WeatherIcon; label: string }

// WMO weather codes, grouped coarsely, each with one of the package's icons.
const WEATHER_KINDS: [maxCode: number, label: string, icon: WeatherIcon][] = [
  [0, '晴', 'sun'],
  [2, '多云', 'cloud-sun'],
  [3, '阴', 'cloud'],
  [48, '雾', 'cloud-fog'],
  [57, '毛毛雨', 'cloud-drizzle'],
  [67, '雨', 'cloud-rain'],
  [77, '雪', 'snowflake'],
  [82, '阵雨', 'cloud-rain'],
  [86, '阵雪', 'snowflake'],
  [99, '雷雨', 'cloud-lightning'],
]

/** `晴 · 12–20°C` with its icon, or nothing for a code outside the table. */
export function describeWeather(day: DayWeather): WeatherDescription | undefined {
  const kind = WEATHER_KINDS.find(([maxCode]) => day.code <= maxCode)
  return kind && { icon: kind[2], label: `${kind[1]} · ${day.min}–${day.max}°C` }
}
