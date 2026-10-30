import { describe, expect, test } from 'vite-plus/test'
import { getSearchTerms, getSnippet, markTerms, searchEntries, type SearchEntry } from './search.ts'

const moon: SearchEntry = {
  kind: 'moment',
  url: '/m/moon/',
  date: '2026-07-10',
  text: '在家拍了几张月亮。',
  photos: [
    { alt: '夜空里的满月', src: 'a.webp', width: 1, height: 1 },
    { alt: '夜空里暖黄色的月亮', src: 'b.webp', width: 1, height: 1 },
  ],
}
const skewers: SearchEntry = {
  kind: 'moment',
  url: '/m/skewers/',
  date: '2026-08-01',
  text: '村里路边的野摊烧烤',
  place: '野摊烧烤',
  photos: [{ alt: '一盘烤得焦香的肉串', src: 'c.webp', width: 1, height: 1 }],
}
const post: SearchEntry = {
  kind: 'post',
  url: '/zeekr/',
  date: '2025-09-01',
  title: 'Zeekr 007GT',
  text: '提车一个月的感受',
}

describe('searchEntries', () => {
  test('finds a term in the words, a place, a title or a photo, newest first', () => {
    expect(searchEntries([moon, skewers, post], '肉串').map((result) => result.entry.url)).toEqual([
      '/m/skewers/',
    ])
    expect(searchEntries([moon, skewers, post], '野摊')[0]!.entry).toBe(skewers)
    expect(searchEntries([moon, skewers, post], 'zeekr')[0]!.entry).toBe(post)
    expect(searchEntries([moon, skewers, post], '夜空').map((result) => result.entry)).toEqual([
      moon,
    ])
  })

  test('needs every term, from the words and photos together', () => {
    expect(searchEntries([moon, skewers], '月亮 暖黄')).toHaveLength(1)
    expect(searchEntries([moon, skewers], '月亮 烧烤')).toHaveLength(0)
  })

  test('keeps only the photos whose alt text holds a term', () => {
    expect(searchEntries([moon], '满月')[0]!.photos.map((photo) => photo.src)).toEqual(['a.webp'])
    expect(searchEntries([moon], '拍了')[0]!.photos).toEqual([])
  })

  test('finds nothing for an empty query', () => {
    expect(searchEntries([moon], '   ')).toEqual([])
  })
})

test('getSearchTerms splits at spaces and ignores case and width', () => {
  expect(getSearchTerms(' ＺＥＥＫＲ  月亮 月亮 ')).toEqual(['zeekr', '月亮'])
})

test('getSnippet cuts around the first term', () => {
  const text = `${'前'.repeat(100)}月亮${'后'.repeat(100)}`
  const snippet = getSnippet(text, ['月亮'], 20)
  expect(snippet).toContain('月亮')
  expect(snippet.startsWith('…')).toBe(true)
  expect(snippet.endsWith('…')).toBe(true)
  expect(getSnippet('短句', ['没有'])).toBe('短句')
})

test('markTerms marks each term, the longer one first', () => {
  expect(markTerms('Zeekr 的月亮', ['zeekr', '月'])).toEqual([
    { text: 'Zeekr', marked: true },
    { text: ' 的', marked: false },
    { text: '月', marked: true },
    { text: '亮', marked: false },
  ])
  expect(markTerms('…月亮椅', ['月亮'])).toEqual([
    { text: '…', marked: false },
    { text: '月亮', marked: true },
    { text: '椅', marked: false },
  ])
})
