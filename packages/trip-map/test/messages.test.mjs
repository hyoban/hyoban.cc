import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { formatDate, formatDateRange, getDateParts } from '../src/format.ts'
import { resolveMessages, t, zhCN } from '../src/messages.ts'
import { describeWeather } from '../src/weather.ts'

test("fills a message's slots, leaving unknown ones alone", () => {
  assert.equal(t(zhCN, 'dayLabel', { day: 3 }), '第 3 天')
  assert.equal(t(zhCN, 'placeEyebrow', { count: 2 }), '2 条记录 · {years}')
})

test('a site replaces some words and keeps the rest', () => {
  const messages = resolveMessages({ locale: 'en-US', summary: 'Recap' })

  assert.equal(messages.summary, 'Recap')
  assert.equal(messages.intro, zhCN.intro)
  assert.equal(formatDate('2026-10-01', messages.locale), 'October 1')
})

test('formats dates in the locale, in UTC, and takes them apart for rolling digits', () => {
  assert.equal(formatDate('2026-10-01', 'zh-CN', { weekday: true }), '10月1日周四')
  assert.equal(formatDateRange('2026-09-12', '2026-09-16', 'zh-CN'), '9月12日 – 9月16日')
  assert.deepEqual(
    getDateParts('2026-10-01', 'zh-CN').map((part) => [part.type, part.value]),
    [
      ['month', '10'],
      ['literal', '月'],
      ['day', '1'],
      ['literal', '日'],
      ['weekday', '周四'],
    ],
  )
})

test("describes a day's weather in words, from its WMO code", () => {
  assert.deepEqual(describeWeather({ code: 0, max: 20, min: 12 }), {
    icon: 'sun',
    label: '晴 · 12–20°C',
  })
  assert.equal(describeWeather({ code: 100, max: 1, min: 0 }), undefined)
})
