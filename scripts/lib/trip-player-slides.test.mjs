import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import {
  buildSlides,
  getSlideDuration,
  getSlideLabel,
  getStopEyebrow,
  getStopPointEnds,
  getWaypointsBefore,
  pickCovers,
} from '../../src/trip-player/slides.ts'

const place = (name) => ({ name })
const stop = (day, places, waypoints = [], images = []) => ({
  date: `第 ${day} 天`,
  day,
  images,
  name: places.join('、'),
  places: places.map(place),
  text: '',
  waypoints: waypoints.map(([name, waypointDay]) => ({
    date: `第 ${waypointDay ?? day} 天`,
    day: waypointDay ?? day,
    name,
  })),
})

test('opens each day before its first stop', () => {
  const stops = [stop(1, ['烟台']), stop(1, ['渤海']), stop(2, ['大连'])]

  assert.deepEqual(
    buildSlides(stops).map((slide) =>
      slide.type === 'stop'
        ? `stop ${slide.stopIndex}`
        : slide.type === 'day'
          ? `day ${slide.day}`
          : slide.type,
    ),
    ['intro', 'day 1', 'stop 0', 'stop 1', 'day 2', 'stop 2', 'summary'],
  )
})

test("lists a day's places once, leaving out waypoints from the night before", () => {
  const stops = [
    stop(1, ['合肥']),
    stop(2, ['南京', '合肥'], [['无为', 1], ['南京']]),
    stop(2, ['南京']),
  ]
  const days = buildSlides(stops).filter((slide) => slide.type === 'day')

  assert.deepEqual(
    days.map(({ date, places, stopIndex }) => [date, places, stopIndex]),
    [
      ['第 1 天', ['合肥'], 0],
      ['第 2 天', ['南京', '合肥'], 1],
    ],
  )
})

test("counts route points up to each stop's own places", () => {
  assert.deepEqual(
    getStopPointEnds([stop(1, ['a']), stop(1, ['b', 'c'], [['w']]), stop(2, [])]),
    [1, 4, 4],
  )
})

test('prefers hand-picked covers', () => {
  assert.deepEqual(pickCovers(['x', 'y', 'z'], [stop(1, ['a'], [], ['p'])], 2), ['x', 'y'])
})

test("falls back to each day's first photo, then the other stops' first photos", () => {
  const stops = [
    stop(1, ['a'], [], ['1a', '1b']),
    stop(1, ['b'], [], ['1c']),
    stop(2, ['c'], [], []),
    stop(3, ['d'], [], ['3a']),
  ]

  assert.deepEqual(pickCovers([], stops, 9), ['1a', '3a', '1c'])
  assert.deepEqual(pickCovers([], stops, 2), ['1a', '3a'])
})

test('times stops by their photos and text, and leaves the summary open', () => {
  const stopSlide = (images, text) => ({
    stop: { ...stop(1, ['a'], [], Array.from({ length: images })), text },
    stopIndex: 0,
    type: 'stop',
  })

  assert.equal(getSlideDuration({ type: 'intro' }), 3000)
  assert.equal(getSlideDuration(stopSlide(0, '')), 5000)
  assert.equal(getSlideDuration(stopSlide(4, '')), 8000)
  assert.equal(getSlideDuration(stopSlide(1, '字'.repeat(50))), 6000)
  assert.equal(getSlideDuration({ type: 'summary' }), undefined)
})

test('passes waypoints from the evening before ahead of the day opener', () => {
  // Day 1 ends at 合肥; day 2 sets off 无为 and 南京 the evening before, then 烟台.
  const stops = [
    stop(1, ['合肥']),
    stop(2, ['烟台'], [['无为', 1], ['南京', 1], ['威海']]),
    stop(2, ['渤海']),
  ]
  const ends = getStopPointEnds(stops)
  const slides = buildSlides(stops)
  const before = (type, stopIndex) =>
    getWaypointsBefore(
      slides.find((slide) => slide.type === type && slide.stopIndex === stopIndex),
      stops,
      ends,
      0,
    )

  assert.deepEqual(before('day', 1), [1, 2])
  assert.deepEqual(before('stop', 1), [3, 1])
  assert.deepEqual(before('stop', 2), [5, 0])
  assert.deepEqual(getWaypointsBefore({ type: 'summary' }, stops, ends, 2), [6, 2])
})

test('a stop later in the day passes all its waypoints itself', () => {
  const stops = [stop(1, ['a']), stop(1, ['b'], [['w', 1]])]

  assert.deepEqual(
    getWaypointsBefore(buildSlides(stops)[3], stops, getStopPointEnds(stops), 0),
    [1, 1],
  )
})

test('names slides and stops for labels', () => {
  const [, day, first] = buildSlides([{ ...stop(2, ['烟台']), name: '烟台港' }])

  assert.equal(getSlideLabel(day), '第 2 天 · 第 2 天')
  assert.equal(getSlideLabel(first), '第 2 天 · 烟台港')
  assert.equal(
    getStopEyebrow({ date: '10月1日', day: 2, time: '14:04' }),
    '第 2 天 · 10月1日 · 14:04',
  )
  assert.equal(getStopEyebrow({ date: '10月1日', day: 2, time: undefined }), '第 2 天 · 10月1日')
})

test('gives a day with only waypoints its own opener, and drives it while that is up', () => {
  // 9/30 drives 无为 → 南京 with no Moment; the first Moment is in 烟台 on day 2.
  const stops = [
    stop(
      2,
      ['烟台'],
      [
        ['无为', 1],
        ['南京', 1],
      ],
    ),
    stop(3, ['大连']),
  ]
  const slides = buildSlides(stops)
  const ends = getStopPointEnds(stops)

  assert.deepEqual(
    slides.map((slide) =>
      slide.type === 'day'
        ? `day ${slide.day}${slide.waypointsOnly ? ' (travel)' : ''} ${slide.places.join('→')}`
        : slide.type,
    ),
    ['intro', 'day 1 (travel) 无为→南京', 'day 2 烟台', 'stop', 'day 3 大连', 'stop', 'summary'],
  )
  assert.deepEqual(
    slides.slice(1, 4).map((slide) => getWaypointsBefore(slide, stops, ends, 0)),
    [
      [0, 0],
      [0, 2],
      [2, 0],
    ],
  )
})

test('passes the evening before a travel day ahead of its opener', () => {
  // Day 1 ends in 合肥 and drives on to 南京 that evening; day 2 only passes 济南.
  const stops = [
    stop(1, ['合肥']),
    stop(
      3,
      ['烟台'],
      [
        ['南京', 1],
        ['济南', 2],
      ],
    ),
  ]
  const slides = buildSlides(stops)
  const ends = getStopPointEnds(stops)
  const days = slides.filter((slide) => slide.type === 'day')

  assert.deepEqual(
    days.map((slide) => [slide.day, Boolean(slide.waypointsOnly)]),
    [
      [1, false],
      [2, true],
      [3, false],
    ],
  )
  assert.deepEqual(getWaypointsBefore(days[1], stops, ends, 0), [1, 1])
  assert.deepEqual(getWaypointsBefore(days[2], stops, ends, 0), [2, 1])
})
