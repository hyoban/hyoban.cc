type IsoDate = `${number}-${number}-${number}`

type HolidayId = keyof typeof holidayNames

type HolidayPeriod = {
  daysOff: readonly [start: IsoDate, end: IsoDate]
  holidays: readonly HolidayId[]
  workdays: readonly IsoDate[]
}

type HolidaySchedule = {
  periods: readonly HolidayPeriod[]
  source: string
  year: number
}

export type ChinaCalendarDay = Readonly<{
  kind: 'day-off' | 'workday'
  name: string
}>

const holidayNames = {
  'dragon-boat': '端午',
  'labor-day': '五一',
  'mid-autumn': '中秋',
  'national-day': '国庆',
  'new-year': '元旦',
  qingming: '清明',
  'spring-festival': '新春',
} as const

const schedules: readonly HolidaySchedule[] = [
  {
    year: 2023,
    source: 'https://www.gov.cn/zhengce/content/2022-12/08/content_5730844.htm',
    periods: [
      { holidays: ['new-year'], daysOff: ['2022-12-31', '2023-01-02'], workdays: [] },
      {
        holidays: ['spring-festival'],
        daysOff: ['2023-01-21', '2023-01-27'],
        workdays: ['2023-01-28', '2023-01-29'],
      },
      { holidays: ['qingming'], daysOff: ['2023-04-05', '2023-04-05'], workdays: [] },
      {
        holidays: ['labor-day'],
        daysOff: ['2023-04-29', '2023-05-03'],
        workdays: ['2023-04-23', '2023-05-06'],
      },
      {
        holidays: ['dragon-boat'],
        daysOff: ['2023-06-22', '2023-06-24'],
        workdays: ['2023-06-25'],
      },
      {
        holidays: ['mid-autumn', 'national-day'],
        daysOff: ['2023-09-29', '2023-10-06'],
        workdays: ['2023-10-07', '2023-10-08'],
      },
    ],
  },
  {
    year: 2024,
    source: 'https://www.gov.cn/zhengce/content/202310/content_6911527.htm',
    periods: [
      { holidays: ['new-year'], daysOff: ['2023-12-30', '2024-01-01'], workdays: [] },
      {
        holidays: ['spring-festival'],
        daysOff: ['2024-02-10', '2024-02-17'],
        workdays: ['2024-02-04', '2024-02-18'],
      },
      {
        holidays: ['qingming'],
        daysOff: ['2024-04-04', '2024-04-06'],
        workdays: ['2024-04-07'],
      },
      {
        holidays: ['labor-day'],
        daysOff: ['2024-05-01', '2024-05-05'],
        workdays: ['2024-04-28', '2024-05-11'],
      },
      { holidays: ['dragon-boat'], daysOff: ['2024-06-08', '2024-06-10'], workdays: [] },
      {
        holidays: ['mid-autumn'],
        daysOff: ['2024-09-15', '2024-09-17'],
        workdays: ['2024-09-14'],
      },
      {
        holidays: ['national-day'],
        daysOff: ['2024-10-01', '2024-10-07'],
        workdays: ['2024-09-29', '2024-10-12'],
      },
    ],
  },
  {
    year: 2025,
    source: 'https://www.gov.cn/zhengce/zhengceku/202411/content_6986383.htm',
    periods: [
      { holidays: ['new-year'], daysOff: ['2025-01-01', '2025-01-01'], workdays: [] },
      {
        holidays: ['spring-festival'],
        daysOff: ['2025-01-28', '2025-02-04'],
        workdays: ['2025-01-26', '2025-02-08'],
      },
      { holidays: ['qingming'], daysOff: ['2025-04-04', '2025-04-06'], workdays: [] },
      {
        holidays: ['labor-day'],
        daysOff: ['2025-05-01', '2025-05-05'],
        workdays: ['2025-04-27'],
      },
      { holidays: ['dragon-boat'], daysOff: ['2025-05-31', '2025-06-02'], workdays: [] },
      {
        holidays: ['national-day', 'mid-autumn'],
        daysOff: ['2025-10-01', '2025-10-08'],
        workdays: ['2025-09-28', '2025-10-11'],
      },
    ],
  },
  {
    year: 2026,
    source: 'https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm',
    periods: [
      {
        holidays: ['new-year'],
        daysOff: ['2026-01-01', '2026-01-03'],
        workdays: ['2026-01-04'],
      },
      {
        holidays: ['spring-festival'],
        daysOff: ['2026-02-15', '2026-02-23'],
        workdays: ['2026-02-14', '2026-02-28'],
      },
      { holidays: ['qingming'], daysOff: ['2026-04-04', '2026-04-06'], workdays: [] },
      {
        holidays: ['labor-day'],
        daysOff: ['2026-05-01', '2026-05-05'],
        workdays: ['2026-05-09'],
      },
      { holidays: ['dragon-boat'], daysOff: ['2026-06-19', '2026-06-21'], workdays: [] },
      { holidays: ['mid-autumn'], daysOff: ['2026-09-25', '2026-09-27'], workdays: [] },
      {
        holidays: ['national-day'],
        daysOff: ['2026-10-01', '2026-10-07'],
        workdays: ['2026-09-20', '2026-10-10'],
      },
    ],
  },
]

const calendarDays = buildCalendarDays(schedules)

export function getChinaCalendarDay(dateKey: string) {
  return calendarDays.get(dateKey)
}

export function getChinaCalendarDayDescription(day: ChinaCalendarDay) {
  return day.kind === 'day-off'
    ? `${day.name}假期`
    : `${day.name}调休上班`
}

function buildCalendarDays(holidaySchedules: readonly HolidaySchedule[]) {
  const days = new Map<string, ChinaCalendarDay>()

  for (const schedule of holidaySchedules) {
    for (const period of schedule.periods) {
      const name = period.holidays.map(holiday => holidayNames[holiday]).join('、')

      for (const dateKey of expandDateRange(period.daysOff)) {
        addCalendarDay(days, dateKey, { kind: 'day-off', name })
      }

      for (const dateKey of period.workdays) {
        addCalendarDay(days, dateKey, { kind: 'workday', name })
      }
    }
  }

  return days
}

function expandDateRange([start, end]: HolidayPeriod['daysOff']) {
  const dates: IsoDate[] = []
  const date = new Date(`${start}T00:00:00Z`)
  const endTime = Date.parse(`${end}T00:00:00Z`)

  while (date.valueOf() <= endTime) {
    dates.push(date.toISOString().slice(0, 10) as IsoDate)
    date.setUTCDate(date.getUTCDate() + 1)
  }

  return dates
}

function addCalendarDay(
  days: Map<string, ChinaCalendarDay>,
  dateKey: IsoDate,
  day: ChinaCalendarDay,
) {
  if (days.has(dateKey)) {
    throw new Error(`Conflicting holiday schedule for ${dateKey}`)
  }

  days.set(dateKey, day)
}
