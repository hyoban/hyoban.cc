import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { chooseLabelPlacement, placeLabel } from '../src/player/label-placement.ts'

const size = { height: 40, width: 120 }
const screen = { bottom: 600, left: 0, right: 800, top: 0 }
const preferred = ['top', 'top-right', 'top-left', 'bottom', 'right', 'left']
const choose = (point, extra = {}) =>
  chooseLabelPlacement({ avoid: [], blocked: [], bounds: screen, point, preferred, size, ...extra })
    .placement

test('opens above the place, clear of its dot', () => {
  const rect = placeLabel('top', { x: 400, y: 300 }, size)

  assert.equal(choose({ x: 400, y: 300 }), 'top')
  assert.ok(rect.bottom <= 300 - 10, 'leaves room for the dot')
  assert.equal(rect.left + size.width / 2, 400)
})

test('turns away from the edges of the screen', () => {
  const onScreen = (point) => {
    const rect = chooseLabelPlacement({
      avoid: [],
      blocked: [],
      bounds: screen,
      point,
      preferred,
      size,
    }).rect
    return rect.left >= 0 && rect.right <= 800 && rect.top >= 0 && rect.bottom <= 600
  }

  assert.equal(choose({ x: 400, y: 20 }), 'bottom')

  for (const point of [
    { x: 400, y: 20 },
    { x: 790, y: 300 },
    { x: 10, y: 300 },
    { x: 790, y: 590 },
  ]) {
    assert.ok(onScreen(point), `label for ${point.x},${point.y} stays on screen`)
  }
})

test('keeps out from under a card and off markers', () => {
  // A card over the top half of the map.
  const card = { bottom: 280, left: 0, right: 800, top: 0 }
  assert.equal(choose({ x: 400, y: 300 }, { blocked: [card] }), 'bottom')

  // The car right above the place.
  const car = { bottom: 290, left: 384, right: 416, top: 258 }
  assert.notEqual(choose({ x: 400, y: 300 }, { avoid: [car] }), 'top')
})

test('stays put unless somewhere else is clearly better', () => {
  // A sliver of the current spot covered isn't worth moving for.
  const sliver = { bottom: 300, left: 330, right: 334, top: 240 }
  assert.equal(choose({ x: 400, y: 300 }, { avoid: [sliver], current: 'top-right' }), 'top-right')
})
