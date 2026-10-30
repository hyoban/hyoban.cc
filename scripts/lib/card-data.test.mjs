import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import {
  getCalendarPanel,
  getCardPhotos,
  getYearPanel,
  isTripInPeriod,
} from '../../apps/web/src/card-data.ts'

const image = (id) => ({
  alt: id,
  uid: id,
  type: 'image',
  src: {
    src: `https://example.com/${id}.jpg`,
    width: 1200,
    height: 800,
    sources: [
      { src: `${id}-480.jpg`, width: 480 },
      { src: `${id}-960.jpg`, width: 960 },
    ],
  },
})
const moment = (dateKey, media = []) => ({
  dateKey,
  id: dateKey,
  uid: dateKey,
  media,
  locations: [],
  occurredAt: `${dateKey}T00:00:00Z`,
  order: 0,
  text: '',
})

test('collages take photos from different records before returning to the first', () => {
  const records = [
    moment('2026-10-01', [image('a'), image('b'), image('c')]),
    moment('2026-10-02', [image('d'), image('e')]),
  ]
  assert.deepEqual(getCardPhotos(records), ['a-960.jpg', 'd-960.jpg', 'b-960.jpg', 'e-960.jpg'])
})

test('utility photos are never picked, and a month of only them is left empty', () => {
  const receipt = { ...image('receipt'), utility: true }
  const records = [
    moment('2025-08-01', [receipt, image('sea')]),
    moment('2025-09-10', [{ ...image('screenshot'), utility: true }]),
  ]
  assert.deepEqual(getCardPhotos(records), ['sea-960.jpg'])
  const { months } = getYearPanel(2025, records)
  assert.equal(months[7].photo, 'sea-480.jpg')
  assert.equal(months[8].photo, undefined)
})

test('duplicate originals never fill multiple tiles, including a video poster', () => {
  const records = [
    moment('2026-10-01', [image('a'), image('b')]),
    moment('2026-10-02', [
      {
        type: 'video',
        uid: 'video',
        alt: '',
        contentType: 'video/mp4',
        src: 'movie.mp4',
        poster: image('a').src,
      },
      image('c'),
    ]),
  ]
  assert.deepEqual(getCardPhotos(records), ['a-960.jpg', 'b-960.jpg', 'c-960.jpg'])
})

test('empty media and posterless video are skipped, and originals without variants work', () => {
  const original = image('original')
  original.src.sources = []
  const records = [
    moment('2026-10-01'),
    moment('2026-10-02', [
      { type: 'video', uid: 'video', alt: '', contentType: 'video/mp4', src: 'movie.mp4' },
      original,
    ]),
  ]
  assert.deepEqual(getCardPhotos(records), ['https://example.com/original.jpg'])
  assert.deepEqual(getCardPhotos(records, 0), [])
})

test('month panels include leap day, skip neighboring months and mark text-only days', () => {
  const panel = getCalendarPanel(2024, 2, [
    moment('2024-01-31', [image('outside')]),
    moment('2024-02-01'),
    moment('2024-02-29', [image('leap')]),
  ])
  assert.equal(panel.kind, 'calendar')
  assert.equal(panel.days.length, 29)
  assert.deepEqual(panel.days[0], { day: 1, active: true, photo: undefined })
  assert.deepEqual(panel.days[1], { day: 2, active: false, photo: undefined })
  assert.deepEqual(panel.days[28], { day: 29, active: true, photo: 'leap-480.jpg' })
})

test('empty month still has every date', () => {
  const panel = getCalendarPanel(2026, 2, [])
  assert.equal(panel.days.length, 28)
  assert.ok(panel.days.every((day) => !day.active && !day.photo))
})

test('trips are selected by overlap, including trips crossing month and year boundaries', () => {
  const trip = { start: '2025-12-30', end: '2026-01-02' }
  assert.equal(isTripInPeriod(trip, { year: 2025 }), true)
  assert.equal(isTripInPeriod(trip, { year: 2026, month: 1 }), true)
  assert.equal(isTripInPeriod(trip, { year: 2026, month: 2 }), false)
  assert.equal(isTripInPeriod(trip, { year: 2024 }), false)
  assert.equal(isTripInPeriod(trip), true)
})
