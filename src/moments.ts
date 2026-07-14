import type { ImageMetadata } from 'astro'
import type { CollectionEntry } from 'astro:content'
import { getCollection } from 'astro:content'

const TIME_ZONE = 'Asia/Singapore'

const dateFormatter = new Intl.DateTimeFormat('en-CA', {
  day: '2-digit',
  month: '2-digit',
  timeZone: TIME_ZONE,
  year: 'numeric',
})

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  hourCycle: 'h23',
  minute: '2-digit',
  timeZone: TIME_ZONE,
})

const imageModules = import.meta.glob<ImageMetadata>(
  '/src/content/moments/**/*.{avif,gif,jpeg,jpg,png,webp}',
  { eager: true, import: 'default' },
)

const videoModules = import.meta.glob<string>(
  '/src/content/moments/**/*.mp4',
  { eager: true, import: 'default', query: '?url' },
)

const momentDocuments = import.meta.glob<string>(
  '/src/content/moments/**/index.md',
  { eager: true, import: 'default', query: '?raw' },
)

type MomentEntry = CollectionEntry<'moments'>

export type ResolvedMomentMedia =
  | {
      alt: string
      src: ImageMetadata
      type: 'image'
    }
  | {
      alt: string
      poster?: ImageMetadata
      src: string
      type: 'video'
    }

export type Moment = {
  dateKey: string
  id: string
  media: ResolvedMomentMedia[]
  publishedAt: Date
  publishedLabel: string
  sourceUrl?: string
  text: string
}

let momentsPromise: Promise<Moment[]> | undefined

export function getMoments() {
  momentsPromise ??= loadMoments()
  return momentsPromise
}

export function getActiveDateKeys(moments: Moment[]) {
  return [...new Set(moments.map(moment => moment.dateKey))]
}

export function getArchiveMonthKeys(moments: Moment[]) {
  const activeDates = getActiveDateKeys(moments)

  if (activeDates.length === 0) {
    return []
  }

  const [startYear, startMonth] = parseMonthKey(activeDates[0]!)
  const [endYear, endMonth] = parseMonthKey(activeDates.at(-1)!)
  const months = []
  let year = startYear
  let month = startMonth

  while (year < endYear || (year === endYear && month <= endMonth)) {
    months.push(`${year}-${String(month).padStart(2, '0')}`)
    month += 1

    if (month === 13) {
      month = 1
      year += 1
    }
  }

  return months
}

export function getMomentDateKey(date: Date) {
  const parts = getDateParts(date)
  return `${parts.year}-${parts.month}-${parts.day}`
}

export function getMomentPath(dateKey: string) {
  return `/calendar/${dateKey.replaceAll('-', '/')}`
}

export function getMonthPath(monthKey: string) {
  return `/calendar/${monthKey.replace('-', '/')}`
}

async function loadMoments() {
  const entries = await getCollection('moments')

  return entries
    .map(resolveMoment)
    .sort((first, second) => (
      first.dateKey.localeCompare(second.dateKey)
      || first.publishedAt.valueOf() - second.publishedAt.valueOf()
    ))
}

function resolveMoment(entry: MomentEntry): Moment {
  const publishedDateKey = getMomentDateKey(entry.data.publishedAt)
  const dateKey = entry.data.occurredOn ?? publishedDateKey
  const moment: Moment = {
    dateKey,
    id: entry.id,
    media: entry.data.media.map(item => resolveMedia(entry, item)),
    publishedAt: entry.data.publishedAt,
    publishedLabel: formatPublishedLabel(entry.data.publishedAt, dateKey),
    text: getMomentText(entry),
  }

  if (entry.data.sourceUrl) {
    moment.sourceUrl = entry.data.sourceUrl
  }

  return moment
}

function formatPublishedLabel(publishedAt: Date, dateKey: string) {
  const time = timeFormatter.format(publishedAt)

  if (getMomentDateKey(publishedAt) === dateKey) {
    return time
  }

  const parts = getDateParts(publishedAt)
  return `发布于 ${parts.year}/${Number(parts.month)}/${Number(parts.day)} ${time}`
}

function getMomentText(entry: MomentEntry) {
  const key = `/src/content/moments/${entry.id}/index.md`
  const document = momentDocuments[key]

  if (!document) {
    throw new Error(`Missing moment document: ${key}`)
  }

  const body = document.match(/^---\r?\n[\s\S]*?\r?\n---\r?\n([\s\S]*)$/)?.[1]

  if (body === undefined) {
    throw new Error(`Invalid moment frontmatter: ${key}`)
  }

  return body.trim()
}

function resolveMedia(entry: MomentEntry, media: MomentEntry['data']['media'][number]): ResolvedMomentMedia {
  const directory = entry.id.replace(/\/index$/, '')
  const key = `/src/content/moments/${directory}/${media.file}`

  if (media.type === 'image') {
    const src = imageModules[key]

    if (!src) {
      throw new Error(`Missing moment image: ${key}`)
    }

    return {
      alt: media.alt,
      src,
      type: 'image',
    }
  }

  const src = videoModules[key]
  const posterKey = media.poster
    ? `/src/content/moments/${directory}/${media.poster}`
    : undefined
  const poster = posterKey ? imageModules[posterKey] : undefined

  if (!src) {
    throw new Error(`Missing moment video: ${key}`)
  }

  if (posterKey && !poster) {
    throw new Error(`Missing moment video poster: ${posterKey}`)
  }

  return {
    alt: media.alt,
    src,
    type: 'video',
    ...(poster ? { poster } : {}),
  }
}

function parseMonthKey(key: string): [number, number] {
  const year = Number.parseInt(key.slice(0, 4), 10)
  const month = Number.parseInt(key.slice(5, 7), 10)

  if (!Number.isInteger(year) || !Number.isInteger(month)) {
    throw new Error(`Invalid month key: ${key}`)
  }

  return [year, month]
}

function getDateParts(date: Date) {
  return Object.fromEntries(
    dateFormatter
      .formatToParts(date)
      .map(part => [part.type, part.value]),
  ) as Record<'day' | 'month' | 'year', string>
}
