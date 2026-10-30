import type { CollectionEntry } from 'astro:content'
import { getCollection } from 'astro:content'

export type PostEntry = CollectionEntry<'posts'>
export type PageEntry = CollectionEntry<'pages'>
export type MarkdownEntry = PostEntry | PageEntry

const postDateFormatter = new Intl.DateTimeFormat('en-CA', {
  day: '2-digit',
  month: '2-digit',
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
})

const pageModules = import.meta.glob('./content/pages/**/*.md')

export function formatPostYear(date: Date) {
  return getPostDateParts(date).year
}

export function formatPostListDate(date: Date) {
  const { day, month } = getPostDateParts(date)
  return `${month}/${day}`
}

export function formatPostFullDate(date: Date) {
  const { day, month, year } = getPostDateParts(date)
  return `${year} 年 ${Number.parseInt(month, 10)} 月 ${Number.parseInt(day, 10)} 日`
}

// Counts a post's markdown the way a Chinese reader would: each CJK character is a word, and
// so is each run of Latin letters or digits. Code blocks, images, link targets and HTML are
// left out, since they are not read as prose.
export function countPostWords(markdown: string) {
  const prose = markdown
    .replaceAll(/^(`{3,}|~{3,})[^\n]*\n[\s\S]*?^\1[^\S\n]*$/gm, '')
    .replaceAll(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replaceAll(/\]\([^)]*\)/g, ']')
    .replaceAll(/<[^>]+>/g, '')
    .replaceAll(/https?:\/\/\S+/g, '')
  const cjk =
    prose.match(/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/gu)
      ?.length ?? 0
  const latin =
    prose.match(/[\p{Script=Latin}\p{Nd}]+(?:[.'’-][\p{Script=Latin}\p{Nd}]+)*/gu)?.length ?? 0
  return cjk + latin
}

export function formatPostWordCount(count: number) {
  return `${count.toLocaleString('zh-CN')} 字`
}

export async function getSortedPosts() {
  return (await getCollection('posts')).sort(
    (a, b) => b.data.pubDate.valueOf() - a.data.pubDate.valueOf(),
  )
}

export async function getPages() {
  if (Object.keys(pageModules).length === 0) {
    return [] as PageEntry[]
  }

  return getCollection('pages')
}

export async function getMarkdownEntries() {
  const [posts, pages] = await Promise.all([getSortedPosts(), getPages()])
  const entries: MarkdownEntry[] = [...posts, ...pages]

  const linkMap = new Map<string, string>()

  for (const entry of entries) {
    const location = `${entry.collection}/${entry.id}`
    const duplicated = linkMap.get(entry.data.link)

    if (duplicated) {
      throw new Error(`Duplicate link "${entry.data.link}" found in ${duplicated} and ${location}.`)
    }

    linkMap.set(entry.data.link, location)
  }

  return entries
}

function getPostDateParts(date: Date) {
  const parts = Object.fromEntries(
    postDateFormatter.formatToParts(date).map((part) => [part.type, part.value]),
  )

  return {
    day: parts.day!,
    month: parts.month!,
    year: parts.year!,
  }
}
