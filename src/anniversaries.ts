type AnnualDate =
  | {
      calendar: 'gregorian'
      day: number
      month: number
    }
  | {
      calendar: 'lunar'
      day: number
      leapMonth?: boolean
      month: number
    }

type AnniversaryOccurrence = {
  date: AnnualDate
  years: number
}

type AnniversaryDefinition = {
  date: AnnualDate
  describe: (occurrence: AnniversaryOccurrence) => string
  endYear?: number
  icon: string
  id: string
  label: (occurrence: AnniversaryOccurrence) => string
  startYear: number
}

type BirthdayOptions = {
  birthYear: number
  date: AnnualDate
  icon?: string
  id: string
  name?: string
}

type YearlyAnniversaryOptions = {
  date: AnnualDate
  icon: string
  id: string
  initialLabel: string
  name: string
  startYear: number
}

type AnnualEventOptions = {
  date: AnnualDate
  description?: string
  icon: string
  id: string
  label: string
  startYear?: number
}

type DatedEventOptions = {
  dateKey: string
  description?: string
  icon: string
  id: string
  label: string
}

export type CalendarAnniversary = Readonly<{
  description: string
  icon: string
  id: string
  label: string
}>

const anniversaryDefinitions: readonly AnniversaryDefinition[] = [
  defineBirthday({
    birthYear: 1999,
    date: lunarDate(8, 16),
    icon: 'i-lucide-cake-slice',
    id: 'birthday',
  }),
  defineYearlyAnniversary({
    date: gregorianDate(8, 17),
    icon: 'i-lucide-car-front',
    id: 'car',
    initialLabel: '提车日',
    name: '提车',
    startYear: 2025,
  }),
  defineAnnualEvent({
    date: gregorianDate(12, 16),
    icon: 'i-lucide-rotate-ccw',
    id: 'driver-license-points-reset',
    label: '驾驶证清分',
  }),
  defineDatedEvent({
    dateKey: '2028-12-16',
    icon: 'i-lucide-calendar-clock',
    id: 'driver-license-expiry',
    label: '驾驶证有效期截止',
  }),
]

const chineseCalendarFormatter = new Intl.DateTimeFormat('zh-CN-u-ca-chinese', {
  day: 'numeric',
  month: 'long',
  timeZone: 'Asia/Shanghai',
})

const lunarMonthNames = [
  '正月',
  '二月',
  '三月',
  '四月',
  '五月',
  '六月',
  '七月',
  '八月',
  '九月',
  '十月',
  '十一月',
  '十二月',
] as const

export function getCalendarAnniversaries(dateKey: string): CalendarAnniversary[] {
  const year = Number.parseInt(dateKey.slice(0, 4), 10)

  return anniversaryDefinitions.flatMap((definition) => {
    if (
      year < definition.startYear
      || (definition.endYear !== undefined && year > definition.endYear)
      || !matchesAnnualDate(dateKey, definition.date)
    ) {
      return []
    }

    const occurrence = {
      date: definition.date,
      years: year - definition.startYear,
    }

    return [{
      description: definition.describe(occurrence),
      icon: definition.icon,
      id: definition.id,
      label: definition.label(occurrence),
    }]
  })
}

function defineBirthday(options: BirthdayOptions): AnniversaryDefinition {
  const name = options.name ?? '生日'

  return {
    date: options.date,
    describe: ({ date, years }) => `${years} 岁${name}（${formatAnnualDate(date)}）`,
    icon: options.icon ?? 'i-lucide-cake-slice',
    id: options.id,
    label: ({ years }) => `${name} · ${years} 岁`,
    startYear: options.birthYear,
  }
}

function defineYearlyAnniversary(
  options: YearlyAnniversaryOptions,
): AnniversaryDefinition {
  const format = ({ years }: AnniversaryOccurrence) => years === 0
    ? options.initialLabel
    : `${options.name} ${years} 周年`

  return {
    date: options.date,
    describe: format,
    icon: options.icon,
    id: options.id,
    label: format,
    startYear: options.startYear,
  }
}

function defineAnnualEvent(options: AnnualEventOptions): AnniversaryDefinition {
  const description = options.description ?? options.label

  return {
    date: options.date,
    describe: () => description,
    icon: options.icon,
    id: options.id,
    label: () => options.label,
    startYear: options.startYear ?? 0,
  }
}

function defineDatedEvent(options: DatedEventOptions): AnniversaryDefinition {
  const year = Number.parseInt(options.dateKey.slice(0, 4), 10)
  const month = Number.parseInt(options.dateKey.slice(5, 7), 10)
  const day = Number.parseInt(options.dateKey.slice(8, 10), 10)

  return {
    ...defineAnnualEvent({
      date: gregorianDate(month, day),
      icon: options.icon,
      id: options.id,
      label: options.label,
      startYear: year,
      ...(options.description === undefined ? {} : { description: options.description }),
    }),
    endYear: year,
  }
}

function gregorianDate(month: number, day: number): AnnualDate {
  return { calendar: 'gregorian', day, month }
}

function lunarDate(month: number, day: number, leapMonth = false): AnnualDate {
  return { calendar: 'lunar', day, leapMonth, month }
}

function matchesAnnualDate(dateKey: string, annualDate: AnnualDate) {
  if (annualDate.calendar === 'gregorian') {
    return Number.parseInt(dateKey.slice(5, 7), 10) === annualDate.month
      && Number.parseInt(dateKey.slice(8, 10), 10) === annualDate.day
  }

  const chineseDate = getChineseDate(dateKey)

  return chineseDate.month === annualDate.month
    && chineseDate.day === annualDate.day
    && chineseDate.leapMonth === Boolean(annualDate.leapMonth)
}

function getChineseDate(dateKey: string) {
  const date = new Date(`${dateKey}T12:00:00Z`)
  const parts = Object.fromEntries(
    chineseCalendarFormatter
      .formatToParts(date)
      .map(part => [part.type, part.value]),
  )
  const monthName = parts.month ?? ''
  const leapMonth = monthName.startsWith('闰')
  const normalizedMonthName = leapMonth ? monthName.slice(1) : monthName

  return {
    day: Number.parseInt(parts.day ?? '', 10),
    leapMonth,
    month: lunarMonthNames.indexOf(normalizedMonthName as typeof lunarMonthNames[number]) + 1,
  }
}

function formatAnnualDate(date: AnnualDate) {
  if (date.calendar === 'gregorian') {
    return `${date.month} 月 ${date.day} 日`
  }

  const monthName = lunarMonthNames[date.month - 1]
  return `农历${date.leapMonth ? '闰' : ''}${monthName}${toChineseDay(date.day)}`
}

function toChineseDay(day: number) {
  const dayNames = [
    '初一', '初二', '初三', '初四', '初五', '初六', '初七', '初八', '初九', '初十',
    '十一', '十二', '十三', '十四', '十五', '十六', '十七', '十八', '十九', '二十',
    '廿一', '廿二', '廿三', '廿四', '廿五', '廿六', '廿七', '廿八', '廿九', '三十',
  ]

  return dayNames[day - 1] ?? String(day)
}
