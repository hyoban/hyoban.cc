// Weather for a trip's day cards, from the WMO codes Open-Meteo reports.

/** A day's weather as cached: its WMO code and the temperatures in °C. */
export type DayWeather = Readonly<{ code: number; max: number; min: number }>

export type WeatherDescription = { icon: string; label: string }

// WMO weather codes, grouped coarsely, each with a Lucide icon class.
const WEATHER_KINDS: [maxCode: number, label: string, icon: string][] = [
  [0, '晴', 'i-lucide-sun'],
  [2, '多云', 'i-lucide-cloud-sun'],
  [3, '阴', 'i-lucide-cloud'],
  [48, '雾', 'i-lucide-cloud-fog'],
  [57, '毛毛雨', 'i-lucide-cloud-drizzle'],
  [67, '雨', 'i-lucide-cloud-rain'],
  [77, '雪', 'i-lucide-snowflake'],
  [82, '阵雨', 'i-lucide-cloud-rain'],
  [86, '阵雪', 'i-lucide-snowflake'],
  [99, '雷雨', 'i-lucide-cloud-lightning'],
]

/** `晴 · 12–20°C` with its icon, or nothing for a code outside the table. */
export function describeWeather(day: DayWeather): WeatherDescription | undefined {
  const kind = WEATHER_KINDS.find(([maxCode]) => day.code <= maxCode)
  return kind && { icon: kind[2], label: `${kind[1]} · ${day.min}–${day.max}°C` }
}
