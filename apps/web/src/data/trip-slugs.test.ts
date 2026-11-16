import { expect, test } from 'vite-plus/test'
import { getReadableTripIds, getTitleSlug } from './trip-slugs.ts'
import { getTripRedirects } from './trip-redirects.ts'

test("a trip's title reads as its words in pinyin, single characters two at a time", () => {
  expect(getTitleSlug('徐州周末行')).toBe('xuzhou-zhoumo-xing')
  expect(getTitleSlug('床车自驾川西')).toBe('chuangche-zijia-chuanxi')
  expect(getTitleSlug('云南之旅')).toBe('yunnan-zhilv')
  expect(getTitleSlug('Rust 大会 2026')).toBe('rust-dahui-2026')
  expect(getTitleSlug('……')).toBe('')
})

test('each trip is its year and its title, the later of two the same numbered', () => {
  const ids = getReadableTripIds([
    { key: '01m7frqy5bngebpejg223z3zwk', start: '2026-11-13', title: '徐州周末行' },
    { key: '2026-hefei-weekend', start: '2026-03-07', title: '合肥周末' },
    { key: '2026-hefei-again', start: '2026-05-02', title: '合肥周末' },
    { key: '2026-untitled', start: '2026-06-01', title: '……' },
  ])
  expect(Object.fromEntries(ids)).toEqual({
    '01m7frqy5bngebpejg223z3zwk': '2026-xuzhou-zhoumo-xing',
    '2026-hefei-weekend': '2026-hefei-zhoumo',
    '2026-hefei-again': '2026-hefei-zhoumo-2',
    '2026-untitled': '2026-untitled',
  })
})

test("a trip's old addresses lead to its address now, by id and by year, playing too", () => {
  const lines = getTripRedirects(
    new Map([
      ['2026-yunnan', '2026-yunnan-zhilv'],
      ['01m7frqy5bngebpejg223z3zwk', '2026-xuzhou-zhoumo-xing'],
      ['2026-same', '2026-same'],
    ]),
  )
  expect(lines).toEqual([
    '/trip/2026-yunnan/* /trip/2026-yunnan-zhilv/:splat 301',
    '/trip/2026/yunnan/* /trip/2026-yunnan-zhilv/:splat 301',
    '/trip/01m7frqy5bngebpejg223z3zwk/* /trip/2026-xuzhou-zhoumo-xing/:splat 301',
  ])
})
