import { readOccasions } from './data/store.ts'

// The calendar's anniversaries, from the occasion entries the API has (`occasion` in
// packages/data-spec): each a day that matters, on the entry of the day it started, coming
// round each year by the Gregorian or the lunar calendar, or the one day alone.

type AnnualDate =
  | {
      calendar: 'gregorian'
      day: number
      month: number
    }
  | {
      calendar: 'lunar'
      day: number
      leapMonth: boolean
      month: number
    }

/** An occasion as the calendar reads it: the part's fields with its entry's uid and day. */
export type Occasion = {
  uid: string
  /** The day it started, `YYYY-MM-DD`. */
  at: string
  name: string
  repeat?: 'year' | undefined
  calendar?: 'gregorian' | 'lunar' | undefined
  count?: 'age' | 'years' | undefined
  firstLabel?: string | undefined
  note?: string | undefined
}

export type CalendarAnniversary = Readonly<{
  description: string
  icon: string
  id: string
  label: string
}>

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

let siteAnniversaries: AnniversaryDefinition[] | undefined

/** The anniversaries on `dateKey`: the site's occasions, or the `occasions` given, as the tests do. */
export function getCalendarAnniversaries(
  dateKey: string,
  occasions?: readonly Occasion[],
): CalendarAnniversary[] {
  const year = Number.parseInt(dateKey.slice(0, 4), 10)
  const definitions = occasions
    ? occasions.map(defineAnniversary)
    : (siteAnniversaries ??= readOccasions().map(defineAnniversary))

  return definitions.flatMap((definition) => {
    if (
      year < definition.startYear ||
      (definition.endYear !== undefined && year > definition.endYear) ||
      !matchesAnnualDate(dateKey, definition.date)
    ) {
      return []
    }

    const occurrence = {
      date: definition.date,
      years: year - definition.startYear,
    }

    return [
      {
        description: definition.describe(occurrence),
        icon: definition.icon,
        id: definition.id,
        label: definition.label(occurrence),
      },
    ]
  })
}

/**
 * An occasion as the calendar marks it: a birthday counts in 岁, an anniversary in 周年 from
 * its first day (called by `firstLabel`), one not counted keeps its name, and one that doesn't
 * repeat is marked on its day alone.
 */
function defineAnniversary(occasion: Occasion): AnniversaryDefinition {
  const startYear = Number.parseInt(occasion.at.slice(0, 4), 10)
  const date =
    occasion.repeat && occasion.calendar === 'lunar'
      ? { calendar: 'lunar' as const, ...getChineseDate(occasion.at) }
      : {
          calendar: 'gregorian' as const,
          month: Number.parseInt(occasion.at.slice(5, 7), 10),
          day: Number.parseInt(occasion.at.slice(8, 10), 10),
        }
  const first = occasion.firstLabel ?? occasion.name
  const label = ({ years }: AnniversaryOccurrence) => {
    switch (occasion.count) {
      case 'age':
        return `${occasion.name} ${years} 岁`
      case 'years':
        return years === 0 ? first : `${occasion.name} ${years} 周年`
      default:
        return years === 0 ? first : occasion.name
    }
  }
  const describe = (occurrence: AnniversaryOccurrence) =>
    occasion.note ??
    (occasion.count === 'age'
      ? `${occurrence.years} 岁${occasion.name}（${formatAnnualDate(occurrence.date)}）`
      : label(occurrence))

  return {
    date,
    describe,
    ...(occasion.repeat ? {} : { endYear: startYear }),
    icon: getIcon(occasion),
    id: occasion.uid,
    label,
    startYear,
  }
}

/** The icon an occasion is marked with: by what it counts, and whether it comes round. */
function getIcon(occasion: Pick<Occasion, 'count' | 'repeat'>) {
  switch (occasion.count) {
    case 'age':
      return 'i-lucide-cake-slice'
    case 'years':
      return 'i-lucide-party-popper'
    default:
      return occasion.repeat ? 'i-lucide-rotate-ccw' : 'i-lucide-calendar-clock'
  }
}

function matchesAnnualDate(dateKey: string, annualDate: AnnualDate) {
  if (annualDate.calendar === 'gregorian') {
    return (
      Number.parseInt(dateKey.slice(5, 7), 10) === annualDate.month &&
      Number.parseInt(dateKey.slice(8, 10), 10) === annualDate.day
    )
  }

  const chineseDate = getChineseDate(dateKey)

  return (
    chineseDate.month === annualDate.month &&
    chineseDate.day === annualDate.day &&
    chineseDate.leapMonth === annualDate.leapMonth
  )
}

/** The lunar day a Gregorian day falls on. */
function getChineseDate(dateKey: string) {
  const date = new Date(`${dateKey}T12:00:00Z`)
  const parts = Object.fromEntries(
    chineseCalendarFormatter.formatToParts(date).map((part) => [part.type, part.value]),
  )
  const monthName = parts.month ?? ''
  const leapMonth = monthName.startsWith('闰')
  const normalizedMonthName = leapMonth ? monthName.slice(1) : monthName

  return {
    day: Number.parseInt(parts.day ?? '', 10),
    leapMonth,
    month: lunarMonthNames.indexOf(normalizedMonthName as (typeof lunarMonthNames)[number]) + 1,
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
    '初一',
    '初二',
    '初三',
    '初四',
    '初五',
    '初六',
    '初七',
    '初八',
    '初九',
    '初十',
    '十一',
    '十二',
    '十三',
    '十四',
    '十五',
    '十六',
    '十七',
    '十八',
    '十九',
    '二十',
    '廿一',
    '廿二',
    '廿三',
    '廿四',
    '廿五',
    '廿六',
    '廿七',
    '廿八',
    '廿九',
    '三十',
  ]

  return dayNames[day - 1] ?? String(day)
}
