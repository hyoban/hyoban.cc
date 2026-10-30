import {
  getCardPhotos,
  getCalendarPanel,
  getLifePanel,
  getYearPanel,
  isTripInPeriod,
} from '@/card-data'
import cities from '@/data/boundaries/cities.json'
import { buildYearReview, getReviewYears } from '@/year-review'
import type { FogRegions } from '@hyoban/trip-map'
import { getTripCovers } from '@/moments/trip-images'
import { getMediaPosters } from '@/media-card'
import type { APIRoute } from 'astro'
import { createHash } from 'node:crypto'
import { getDocumentTitle, getMapHeading, getTripListHeading } from '@/trip-map'
import { getMapCardBackground, getMapCardPlaces } from '@/map-card'
import {
  cachedCard,
  cardResponse,
  encodeCard,
  getCardFormat,
  renderOgImage,
  type CardFormat,
  type OgImageOptions,
} from '@/og-image'
import { getMapPlaceholderKey } from '@/map-placeholder'
import {
  getActiveDateKeys,
  getArchiveMonthKeys,
  getMapMonthKeys,
  getMapYears,
  getMomentPagePath,
  getMomentsSummary,
  getMoments,
  getTrips,
  MOMENT_OG_IMAGES,
  type Moment,
} from '@/moments'

// The share cards of the pages without one of their own, a post's, a trip's or a
// Moment's: the calendar's months and days, the map's, the trip lists, the year reviews
// and the media page.
// Drawn by /og/pages/<path>.jpg, or .png for a card of words alone, with shorter text
// beside their visual panels.

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

export function getPageTitle({ heading }: Pick<PageText, 'heading'>) {
  return getDocumentTitle(heading)
}

const trimSlashes = (path: string) => path.replace(/^\/+|\/+$/g, '')

/** The share card of the page at `pathname`: /calendar/2026/10/ has /og/pages/calendar/2026/10.jpg. */
export async function getPageCardPath(pathname: string) {
  const path = trimSlashes(pathname)
  const card = (await getPageCards()).find((entry) => trimSlashes(entry.path) === path)?.card
  if (!card) throw new Error(`No share card is drawn for ${pathname}`)
  return `/og/pages/${path}.${getCardFormat(card)}`
}

/**
 * A day of one Moment shares that Moment's card, where Moment cards are drawn: a card of
 * its own would show the same words and photos.
 */
export function getDayMomentCard(dayMoments: Moment[]) {
  const [moment] = dayMoments
  return MOMENT_OG_IMAGES && dayMoments.length === 1 && moment
    ? `${getMomentPagePath(moment)}og.jpg`
    : undefined
}

/** A day as the calendar heads it: `10 月 23 日\u2003星期四`. */
export function formatCalendarDay(dateKey: string) {
  const [year, month, day] = dateKey.split('-').map(Number) as [number, number, number]
  const weekday = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Singapore',
    weekday: 'long',
  }).format(new Date(Date.UTC(year, month - 1, day)))

  return `${month} 月 ${day} 日\u2003${weekday}`
}

export function getCalendarText(year: number, month: number, selectedDate?: string): PageText {
  return {
    heading: selectedDate
      ? `${selectedDate.slice(0, 4)} 年 ${formatCalendarDay(selectedDate)}`
      : `${year} 年 ${month} 月\u2003日历`,
    description: CALENDAR_DESCRIPTION,
  }
}

/** The day's own words, shared by its metadata and image. */
export function getCalendarDayDescription(moments: Moment[]) {
  return getMomentsSummary(moments, 100) || CALENDAR_DESCRIPTION
}

/** The map's pages, narrowed to a year or a month of it or not, and the trip lists. */
export function getMapText(
  period?: { month?: number | undefined; year: number },
  tripList = false,
): PageText {
  return tripList
    ? { heading: getTripListHeading(period), description: '出门走走，记下沿途的风景和小事。' }
    : { heading: getMapHeading(period), description: '去过的地方，留下的记录。' }
}

export interface PageCard extends OgImageOptions {
  title: string
  /** The map period, as getMapPlaceholderKey gives it. */
  map?: string | undefined
}

let pageCards: Promise<Array<{ path: string; card: PageCard }>> | undefined

/** Every page that shows its own card from getPageCardPath, by its path, and what it draws. */
export function getPageCards() {
  pageCards ??= loadPageCards()
  return pageCards
}

/** The route of the cards in `format`, which /og/pages/[...page].png.ts and .jpg.ts export. */
export function pageCardRoute(format: CardFormat) {
  const getStaticPaths = async () => {
    // HTML-only integration builds do not need to fetch fonts and maps for these cards.
    if (process.env.SKIP_PAGE_OG_IMAGES === '1') return []
    return (await getPageCards())
      .filter(({ card }) => getCardFormat(card) === format)
      .map(({ card, path }) => ({ params: { page: trimSlashes(path) }, props: { card } }))
  }
  const GET: APIRoute<{ card: PageCard }> = async ({ props: { card } }) => {
    const { map, ...options } = card
    // A map page's card draws on its map, where the build can draw maps.
    const background = map ? await getMapCardBackground(map) : undefined
    const inputs = {
      ...options,
      background: background && createHash('sha256').update(background).digest('hex'),
    }
    return cardResponse(
      await cachedCard(`page.${format}`, inputs, async () =>
        encodeCard(await renderOgImage({ ...options, background }), format),
      ),
      format,
    )
  }
  return { getStaticPaths, GET }
}

async function loadPageCards(): Promise<Array<{ path: string; card: PageCard }>> {
  const moments = await getMoments()
  const momentsByDate = Map.groupBy(moments, (moment) => moment.dateKey)
  const monthKeys = getArchiveMonthKeys(moments)
  const parseMonth = (key: string) => ({
    year: Number.parseInt(key.slice(0, 4), 10),
    month: Number.parseInt(key.slice(5, 7), 10),
  })
  const cards: Array<{ path: string; card: PageCard }> = []
  const trips = getTrips(moments).toSorted((a, b) => b.start.localeCompare(a.start))
  type Period = { year: number; month?: number | undefined }
  const getPeriodRecords = (period?: Period) =>
    period
      ? moments.filter((moment) =>
          moment.dateKey.startsWith(
            period.month === undefined
              ? `${period.year}-`
              : `${period.year}-${String(period.month).padStart(2, '0')}-`,
          ),
        )
      : moments
  // A period with no places or no trips has a page all the same, the map's and the trip
  // list's narrowed to it: its card says so and shows the period's photos instead.
  const getPeriodPhotos = (records: Moment[]) => {
    const photos = getCardPhotos(records)
    return photos.length ? photos : undefined
  }
  // A card with a picture, a calendar, a map or photos, lets it speak: beside it only the
  // title and one line of counts, with no description.
  const calendarCard = (year: number, month: number): PageCard => {
    const records = getPeriodRecords({ year, month })
    return {
      title: `${year} 年 ${month} 月\n日历`,
      // A month with no Moments keeps its page between those with them.
      detail: records.length
        ? `${new Set(records.map((moment) => moment.dateKey)).size} 天\u2003${records.length} 条记录`
        : '这个月没有记录',
      panel: getCalendarPanel(year, month, records),
    }
  }
  const mapCard = (period?: Period): PageCard => {
    const key = getMapPlaceholderKey(period)
    const places = getMapCardPlaces(key, moments)
    const title = getMapText(period).heading
    if (places.length) return { title, map: key, detail: `${places.length} 个地点` }
    const records = getPeriodRecords(period)
    return {
      title,
      detail: `${records.length} 条记录\u2003都没有地点`,
      photos: getPeriodPhotos(records),
    }
  }
  const tripCard = (period?: Period): PageCard => {
    const selected = trips.filter((trip) => isTripInPeriod(trip, period))
    const title = getMapText(period, true).heading.replace('\u2003', '\n')
    if (!selected.length) {
      const records = getPeriodRecords(period)
      return {
        title,
        detail: `没有出门\u2003${records.length} 条记录`,
        photos: getPeriodPhotos(records),
      }
    }
    const days = selected.reduce((total, trip) => total + trip.dayCount, 0)
    return {
      title,
      detail: `${selected.length} 段旅程\u2003${days} 天`,
      panel: {
        kind: 'trips',
        trips: selected.slice(0, 3).map((trip) => ({
          title: trip.title,
          detail: `${trip.start.replaceAll('-', '.')}\u2003${trip.dayCount} 天`,
          photo: getTripCovers(trip)[0]?.src ?? getCardPhotos(trip.stops, 1)[0],
        })),
      },
    }
  }

  // /calendar/ is the latest month and shares its card (see getPageCardPath's callers).
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
    if (getDayMomentCard(dayMoments)) continue
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

  // A year's review shows its twelve months, a photo each.
  const allTrips = getTrips(moments)
  for (const year of getReviewYears(moments)) {
    const review = buildYearReview(year, moments, allTrips, cities as FogRegions)
    cards.push({
      path: `/${year}/`,
      card: {
        title: `${year} 年\n年度回顾`,
        detail: `${review.dayCount} 天\u2003${review.cities.lit.length} 个城市`,
        panel: getYearPanel(year, review.moments),
      },
    })
  }
  // Every year's, as a year's is: the years it spans, what they add up to, and twelve of
  // their months' photos.
  const years = getReviewYears(moments)
  const life = buildYearReview(undefined, moments, allTrips, cities as FogRegions)
  cards.push({
    path: '/life/',
    card: {
      title: `${years[0]}–${years.at(-1)}\n人生回顾`,
      detail: `${years.length} 年\u2003${life.dayCount} 天\u2003${life.cities.lit.length} 个城市`,
      panel: getLifePanel(years, moments),
    },
  })

  const media = getMediaPosters()
  cards.push({
    path: '/media/',
    card: {
      title: MEDIA_TEXT.heading,
      detail: `${media.count} 部作品`,
      panel: media.photos.length ? { kind: 'posters', photos: media.photos } : undefined,
    },
  })
  return cards
}
