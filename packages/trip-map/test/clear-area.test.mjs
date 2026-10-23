import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { needsFraming, planClearArea } from '../src/map/clear-area.ts'

// A 1000 × 800 map with a 400-wide sidebar on the right: clear from 32 to 568 across.
const BOX = { bottom: 768, left: 32, right: 568, top: 80 }

test('leaves what is already clear where it is', () => {
  const plan = planClearArea(
    [
      { x: 100, y: 200 },
      { x: 400, y: 500 },
    ],
    BOX,
  )

  assert.equal(plan.kind, 'stay')
  assert.equal(needsFraming(plan), false)
})

test('frames anew what is clear but too small to make out', () => {
  const plan = planClearArea(
    [
      { x: 300, y: 300 },
      { x: 310, y: 305 },
    ],
    BOX,
  )

  assert.equal(plan.kind, 'stay')
  assert.equal(needsFraming(plan), true)
})

test('slides out from under the sidebar only as far as it takes', () => {
  assert.deepEqual(
    planClearArea(
      [
        { x: 300, y: 300 },
        { x: 700, y: 400 },
      ],
      BOX,
    ),
    { dx: 132, dy: 0, fill: 400 / 536, kind: 'pan' },
  )
})

test('zooms out for what is partly clear but wider than the clear area', () => {
  assert.deepEqual(
    planClearArea(
      [
        { x: 0, y: 300 },
        { x: 300, y: 350 },
        { x: 900, y: 400 },
      ],
      BOX,
    ),
    { kind: 'zoom-out' },
  )
})

test('flies to what is nowhere in the clear area', () => {
  const plan = planClearArea([{ x: 800, y: 300 }], BOX)

  assert.deepEqual(plan, { kind: 'away' })
  assert.equal(needsFraming(plan), true)
})
