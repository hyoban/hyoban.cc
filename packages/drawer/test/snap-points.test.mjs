import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import {
  closestIndex,
  findActiveSnapPoint,
  getSnapPointSwipeMovement,
  resolveSnapPointHeight,
  resolveSnapPoints,
} from '../src/snap-points.ts'

const metrics = { popupHeight: 800, rootFontSize: 16, viewportHeight: 800 }

test('reads a snap point as a share of the viewport, pixels or rems', () => {
  assert.equal(resolveSnapPointHeight(0.5, 800, 16), 400)
  assert.equal(resolveSnapPointHeight(1, 800, 16), 800)
  assert.equal(resolveSnapPointHeight(320, 800, 16), 320)
  assert.equal(resolveSnapPointHeight('240px', 800, 16), 240)
  assert.equal(resolveSnapPointHeight('20rem', 800, 16), 320)
  assert.equal(resolveSnapPointHeight('50%', 800, 16), null)
  assert.equal(resolveSnapPointHeight(0.5, 0, 16), null)
})

test('rests the popup short of open by what a snap point leaves out of sight', () => {
  assert.deepEqual(resolveSnapPoints([0.5, 1], metrics), [
    { value: 0.5, height: 400, offset: 400 },
    { value: 1, height: 800, offset: 0 },
  ])

  // No taller than the popup, and the later of two the same height kept.
  assert.deepEqual(resolveSnapPoints(['400px', 0.5, 2000], { ...metrics, popupHeight: 600 }), [
    { value: 0.5, height: 400, offset: 200 },
    { value: 2000, height: 600, offset: 0 },
  ])
  assert.deepEqual(resolveSnapPoints([], metrics), [])
  assert.deepEqual(resolveSnapPoints([0.5], { ...metrics, popupHeight: 0 }), [])
})

test('finds the active snap point, or the one nearest its height', () => {
  const resolved = resolveSnapPoints([0.25, 0.5, 1], metrics)

  assert.equal(findActiveSnapPoint(0.5, resolved, metrics)?.offset, 400)
  assert.equal(findActiveSnapPoint('420px', resolved, metrics)?.value, 0.5)
  assert.equal(findActiveSnapPoint(null, resolved, metrics), undefined)
  assert.equal(closestIndex([0, 400, 600], 450), 1)
  assert.equal(closestIndex([], 1), -1)
})

test('resists a drag past fully open', () => {
  assert.equal(getSnapPointSwipeMovement(400, -100), -100)
  assert.equal(getSnapPointSwipeMovement(0, 50), 50)
  // 100px past open moves it only 10px past.
  assert.equal(getSnapPointSwipeMovement(0, -100), -10)
  assert.equal(getSnapPointSwipeMovement(100, -200), -110)
})
