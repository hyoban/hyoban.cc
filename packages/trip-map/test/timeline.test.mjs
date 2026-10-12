import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { buildSlides } from '../src/player/slides.ts'
import {
  findAnchorSlide,
  findDayTarget,
  getProgressAmount,
  getSlideAnchor,
  resolveSlideIndex,
  stepThroughPhotos,
} from '../src/player/timeline.ts'

const stop = (day, anchor, waypoints = []) => ({
  anchor,
  dateKey: `2026-10-0${day}`,
  day,
  images: [],
  places: [{ name: anchor }],
  text: '',
  waypoints: waypoints.map(([name, waypointDay]) => ({
    dateKey: `2026-10-0${waypointDay}`,
    day: waypointDay,
    name,
  })),
})
// intro, day 1, stop a, stop b, day 2, stop c, summary
const stops = [stop(1, 'a'), stop(1, 'b'), stop(2, 'c')]
const slides = buildSlides(stops)

test('skips day openers in the direction of travel while presenting', () => {
  assert.equal(resolveSlideIndex(slides, 0, 1, false), 1)
  assert.equal(resolveSlideIndex(slides, 0, 1, true), 2)
  assert.equal(resolveSlideIndex(slides, 5, 4, true), 3)
})

test('steps a day at a time, to the openers or the stops after them', () => {
  assert.equal(findDayTarget(slides, 2, 1, false), 4)
  assert.equal(findDayTarget(slides, 2, 1, true), 5)
  assert.equal(findDayTarget(slides, 3, -1, false), 1)
  assert.equal(findDayTarget(slides, 5, 1, false), 6)
  assert.equal(findDayTarget(slides, 1, -1, false), 0)
})

test("a shared link names a stop's card or the day it opens", () => {
  assert.equal(findAnchorSlide(slides, stops, 'b'), 3)
  // The first stop of a day is reached through the day's opener.
  assert.equal(findAnchorSlide(slides, stops, 'a'), 1)
  assert.equal(findAnchorSlide(slides, stops, ''), 0)
  assert.equal(findAnchorSlide(slides, stops, 'nope'), 0)
  assert.equal(getSlideAnchor(slides[1], stops), 'a')
  assert.equal(getSlideAnchor(slides[3], stops), 'b')
  assert.equal(getSlideAnchor(slides[0], stops), '')
})

test('a travel day is not what a link names', () => {
  const travel = [stop(2, 'x', [['w', 1]])]
  const travelSlides = buildSlides(travel)

  assert.equal(travelSlides[1].waypointsOnly, true)
  assert.equal(findAnchorSlide(travelSlides, travel, 'x'), 2)
  assert.equal(getSlideAnchor(travelSlides[1], travel), '')
})

test('fills the progress bar up to the current card', () => {
  assert.equal(getProgressAmount(0, 2, 10, 100), 1)
  assert.equal(getProgressAmount(3, 2, 10, 100), 0)
  assert.equal(getProgressAmount(2, 2, 25, 100), 0.25)
  assert.equal(getProgressAmount(2, 2, 500, 100), 1)
  assert.equal(getProgressAmount(2, 2, 0, undefined), 1)
})

test('steps through photos and past their ends to the next card', () => {
  assert.deepEqual(stepThroughPhotos(3, 0, 1), { photo: 1, type: 'photo' })
  assert.deepEqual(stepThroughPhotos(3, 2, 1), { direction: 1, type: 'card' })
  assert.deepEqual(stepThroughPhotos(3, 0, -1), { direction: -1, type: 'card' })
  assert.deepEqual(stepThroughPhotos(0, 0, 1), { direction: 1, type: 'card' })
})
