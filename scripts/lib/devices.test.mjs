import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { getDeviceEvents, getDeviceLanes, getDeviceSpans } from '../../src/data/devices.ts'
import { devicesSchema } from '@hyoban/data-spec'

const devices = devicesSchema.parse({
  'old-phone': { name: 'Old', category: '手机', bought: { on: '2021-01-01', price: 1000 } },
  'new-phone': {
    name: 'New',
    category: '手机',
    bought: { on: '2023-01-01', price: 3000 },
    sold: { on: '2023-01-11', price: 2000 },
  },
  'later-phone': { name: 'Later', category: '手机', bought: { on: '2023-02-01', price: 0 } },
  console: {
    name: 'Console',
    category: '游戏机',
    bought: { on: '2022-01-01', price: 0 },
    idle: true,
  },
})

test('a span ends when the device is sold or today, and costs what it lost per day', () => {
  const spans = getDeviceSpans(devices, '2023-03-01')
  const sold = spans.find((span) => span.id === 'new-phone')

  assert.deepEqual(
    spans.map((span) => [span.id, span.status]),
    [
      ['old-phone', 'backup'],
      ['console', 'idle'],
      ['new-phone', 'sold'],
      ['later-phone', 'in-use'],
    ],
  )
  assert.equal(sold.end, '2023-01-11')
  assert.equal(sold.days, 10)
  assert.equal(sold.dailyCost, 100)
})

test('spans that overlap in a category take another row, the rest share one', () => {
  const lanes = getDeviceLanes(getDeviceSpans(devices, '2023-03-01'))

  assert.deepEqual(
    lanes.map(({ category, rows }) => [category, rows.map((row) => row.map((span) => span.id))]),
    [
      ['手机', [['old-phone'], ['new-phone', 'later-phone']]],
      ['游戏机', [['console']]],
    ],
  )
})

test('the feed lists purchases and sales newest first', () => {
  const events = getDeviceEvents(getDeviceSpans(devices, '2023-03-01'))

  assert.deepEqual(
    events.map(({ date, kind }) => `${date} ${kind}`),
    [
      '2023-02-01 bought',
      '2023-01-11 sold',
      '2023-01-01 bought',
      '2022-01-01 bought',
      '2021-01-01 bought',
    ],
  )
})

test('a device in use is a backup while a newer one of its category is', () => {
  const spans = getDeviceSpans(
    devicesSchema.parse({
      first: { name: 'First', category: '手机', bought: { on: '2021-01-01', price: 1 } },
      second: {
        name: 'Second',
        category: '手机',
        bought: { on: '2022-01-01', price: 1 },
        sold: { on: '2023-01-01', price: 0 },
      },
      third: { name: 'Third', category: '手机', bought: { on: '2023-02-01', price: 1 } },
      shelved: {
        name: 'Shelved',
        category: '手机',
        bought: { on: '2024-01-01', price: 1 },
        idle: true,
      },
    }),
    '2024-06-01',
  )
  const byId = Object.fromEntries(spans.map((span) => [span.id, span]))

  assert.deepEqual(byId.first.backup, [
    { start: '2022-01-01', end: '2023-01-01' },
    { start: '2023-02-01', end: '2024-06-01' },
  ])
  assert.equal(byId.first.status, 'backup')
  assert.deepEqual(byId.second.backup, [])
  assert.equal(byId.third.status, 'in-use')
  assert.deepEqual(byId.shelved.backup, [])
})
