import { SITE_TITLE } from '@/consts'
import { getCardPhotos, getCalendarPanel, isTripInPeriod } from '@/card-data'
import { getMapCardPlaces } from '@/map-card'
import { getTripCovers } from '@/moments/trip-images'
import { getMediaPosters } from '@/media-card'
import type { OgImageOptions } from '@/og-image'
import { getMapPlaceholderKey } from '@/map-placeholder'
import {
  getActiveDateKeys,
  getArchiveMonthKeys,
  getMapMonthKeys,
  getMapYears,
  getMomentExcerpt,
  getMoments,
  getTrips,
  MOMENT_OG_IMAGES,
  type Moment,
} from '@/moments'

// The share cards of the pages without one of their own, a post's, a trip's or a
// Moment's: the calendar's months and days, the map's, the trip lists and the media page.
// Drawn by /og/pages/<path>.png, with shorter text beside their visual panels.

export interface PageText {
  /** The page's title without the site's name, which the card shows apart. */
  heading: string
  description: string
}

export const CALENDAR_DESCRIPTION = '随手拍的照片，和想记住的小事。'
export const MEDIA_TEXT: PageText = {
  heading: '影视推荐',
  description: '我喜欢的电影、剧集和动画。',
}

export function getPageTitle({ heading }: PageText) {
  return `${heading} | ${SITE_TITLE}`
}

/** The share card of the page at `pathname`: /calendar/2026/10/ has /og/pages/calendar/2026/10.png. */
export function getPageCardPath(pathname: string) {
  return `/og/pages/${pathname.replace(/^\/+|\/+$/g, '')}.png`
}

/** A day as the calendar heads it: `10 月 23 日 · 星期四`. */
export function formatCalendarDay(dateKey: string) {
  const [year, month, day] = dateKey.split('-').map(Number) as [number, number, number]
  const weekday = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Singapore',
    weekday: 'long',
  }).format(new Date(Date.UTC(year, month - 1, day)))

  return `${month} 月 ${day} 日 · ${weekday}`
}

export function getCalendarText(year: number, month: number, selectedDate?: string): PageText {
  return {
    heading: selectedDate
      ? `${formatCalendarDay(selectedDate)} · 日历`
      : `${year} 年 ${month} 月 · 日历`,
    description: CALENDAR_DESCRIPTION,
  }
}

/** The day's own words, shared by its metadata and image. */
export function getCalendarDayDescription(moments: Moment[]) {
  return (
    moments.map((moment) => getMomentExcerpt(moment, 120)).find(Boolean) || CALENDAR_DESCRIPTION
  )
}

/** The map's pages, narrowed to a year or a month of it or not, and the trip lists. */
export function getMapText(
  period?: { month?: number | undefined; year: number },
  tripList = false,
): PageText {
  const periodLabel = period
    ? period.month === undefined
      ? `${period.year} 年`
      : `${period.year} 年 ${period.month} 月`
    : undefined
  if (tripList) {
    return {
      heading: periodLabel ? `${periodLabel} · 旅程` : '旅程',
      description: periodLabel
        ? `${periodLabel}的旅程，和一路上的照片与见闻。`
        : '出门走走，记下沿途的风景和小事。',
    }
  }
  return periodLabel
    ? {
        heading: `${periodLabel} · 地图`,
        description: `${periodLabel}的足迹和沿途记录。`,
      }
    : { heading: '地图', description: '去过的地方，留下的记录。' }
}

export interface PageCard extends OgImageOptions {
  title: string
  /** The map period, as getMapPlaceholderKey gives it. */
  map?: string | undefined
}

/** Every page that shows its own card from getPageCardPath, by its path, and what it draws. */
export async function getPageCards(): Promise<Array<{ path: string; card: PageCard }>> {
  const moments = await getMoments()
  const momentsByDate = Map.groupBy(moments, (moment) => moment.dateKey)
  const monthKeys = getArchiveMonthKeys(moments)
  const toCard = ({ heading, description }: PageText): PageCard => ({ title: heading, description })
  const parseMonth = (key: string) => ({
    year: Number.parseInt(key.slice(0, 4), 10),
    month: Number.parseInt(key.slice(5, 7), 10),
  })
  const cards: Array<{ path: string; card: PageCard }> = []
  const trips = getTrips(moments).toSorted((a, b) => b.start.localeCompare(a.start))
  const calendarCard = (year: number, month: number): PageCard => {
    const key = `${year}-${String(month).padStart(2, '0')}`
    const records = moments.filter((moment) => moment.dateKey.startsWith(key))
    return {
      title: `${year} 年 ${month} 月\n日历`,
      description: '照片和日常小事。',
      detail: `${new Set(records.map((moment) => moment.dateKey)).size} 天 · ${records.length} 条记录`,
      panel: getCalendarPanel(year, month, records),
    }
  }
  const mapCard = (period?: { year: number; month?: number | undefined }): PageCard => {
    const key = getMapPlaceholderKey(period)
    const places = getMapCardPlaces(key, moments)
    return {
      title: getMapText(period).heading,
      description: '去过的地方，留下的记录。',
      map: key,
      detail: `${places.length} 个地点`,
    }
  }
  const tripCard = (period?: { year: number; month?: number | undefined }): PageCard => {
    const selected = trips.filter((trip) => isTripInPeriod(trip, period))
    return {
      title: getMapText(period, true).heading.replace(' · ', '\n'),
      description: '沿途的风景和小事。',
      detail: `${selected.length} 段旅程`,
      panel: selected.length
        ? {
            kind: 'trips',
            trips: selected.slice(0, 3).map((trip) => ({
              title: trip.title,
              detail: `${trip.start.replaceAll('-', '.')} · ${trip.dayCount} 天`,
              photo: getTripCovers(trip)[0]?.src ?? getCardPhotos(trip.stops, 1)[0],
            })),
          }
        : undefined,
    }
  }

  const latestMonth = monthKeys.at(-1)
  if (latestMonth) {
    const { month, year } = parseMonth(latestMonth)
    cards.push({ path: '/calendar/', card: calendarCard(year, month) })
  }
  for (const key of monthKeys) {
    const { month, year } = parseMonth(key)
    cards.push({
      path: `/calendar/${key.replace('-', '/')}/`,
      card: calendarCard(year, month),
    })
  }
  // A day's card leads with its first words and, where Moment cards are drawn, its photos.
  for (const dateKey of getActiveDateKeys(moments)) {
    const dayMoments = momentsByDate.get(dateKey) ?? []
    cards.push({
      path: `/calendar/${dateKey.replaceAll('-', '/')}/`,
      card: {
        title: `${dateKey.slice(0, 4)} 年 ${formatCalendarDay(dateKey)}`,
        description: getCalendarDayDescription(dayMoments),
        photos: MOMENT_OG_IMAGES ? getCardPhotos(dayMoments) : undefined,
      },
    })
  }

  cards.push({ path: '/map/', card: mapCard() })
  cards.push({ path: '/trip/', card: tripCard() })
  for (const year of getMapYears(moments)) {
    cards.push({
      path: `/map/${year}/`,
      card: mapCard({ year }),
    })
    cards.push({ path: `/trip/${year}/`, card: tripCard({ year }) })
  }
  for (const key of getMapMonthKeys(moments)) {
    const period = parseMonth(key)
    cards.push({
      path: `/map/${key.replace('-', '/')}/`,
      card: mapCard(period),
    })
    cards.push({ path: `/trip/${key.replace('-', '/')}/`, card: tripCard(period) })
  }

  const media = getMediaPosters()
  cards.push({
    path: '/media/',
    card: {
      ...toCard(MEDIA_TEXT),
      detail: `${media.count} 部作品`,
      panel: media.photos.length ? { kind: 'posters', photos: media.photos } : undefined,
    },
  })
  return cards
}
