import type { WeatherIcon } from './icons.ts'
import { type MessageKey, type Messages, t, zhCN } from './messages.ts'

// Weather for a trip's day cards, from the WMO codes Open-Meteo reports.

/** A day's weather as cached: its WMO code and the temperatures in °C. */
export type DayWeather = Readonly<{ code: number; max: number; min: number }>

export type WeatherDescription = { icon: WeatherIcon; label: string }

// WMO weather codes, grouped coarsely, each with a name and one of the package's icons.
const WEATHER_KINDS: [maxCode: number, kind: MessageKey, icon: WeatherIcon][] = [
  [0, 'weatherClear', 'sun'],
  [2, 'weatherPartlyCloudy', 'cloud-sun'],
  [3, 'weatherOvercast', 'cloud'],
  [48, 'weatherFog', 'cloud-fog'],
  [57, 'weatherDrizzle', 'cloud-drizzle'],
  [67, 'weatherRain', 'cloud-rain'],
  [77, 'weatherSnow', 'snowflake'],
  [82, 'weatherShowers', 'cloud-rain'],
  [86, 'weatherSnowShowers', 'snowflake'],
  [99, 'weatherThunderstorm', 'cloud-lightning'],
]

/** `晴\u200312–20°C` with its icon, or nothing for a code outside the table. */
export function describeWeather(
  day: DayWeather,
  messages: Messages = zhCN,
): WeatherDescription | undefined {
  const kind = WEATHER_KINDS.find(([maxCode]) => day.code <= maxCode)

  return (
    kind && {
      icon: kind[2],
      label: t(messages, 'weatherLabel', { kind: messages[kind[1]], max: day.max, min: day.min }),
    }
  )
}
