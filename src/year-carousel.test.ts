import { expect, test } from 'vite-plus/test'
import { getKeylines, getSteps, placeItems } from './year-carousel.ts'

const round = (items: { x: number; size: number }[]) =>
  items.map(({ x, size }) => [Math.round(x), Math.round(size)])

test('the keylines fill the width with large items, then a medium and a small one', () => {
  const phone = getKeylines(343)
  expect(phone.largeCount).toBe(1)
  expect(phone.small).toBe(48)
  expect(phone.largeCount * phone.stride + phone.medium + phone.gap + phone.small).toBeCloseTo(343)

  const wide = getKeylines(640)
  expect(wide.largeCount).toBe(3)
  expect(wide.medium).toBeCloseTo(wide.large * 0.6)
})

test('the row starts on its large items and ends on them, turned round', () => {
  const keylines = getKeylines(343)
  const { large, medium, small, gap } = keylines
  const steps = getSteps(5, keylines)
  expect(steps).toBe(4)

  // [L, M, S] at the start, the rest small past the edge.
  expect(round(placeItems(5, keylines, 0)).slice(0, 3)).toEqual(
    round([
      { x: 0, size: large },
      { x: large + gap, size: medium },
      { x: large + gap + medium + gap, size: small },
    ]),
  )
  // [S, M, L] at the end.
  expect(round(placeItems(5, keylines, steps)).slice(2)).toEqual(
    round([
      { x: 0, size: small },
      { x: small + gap, size: medium },
      { x: small + gap + medium + gap, size: large },
    ]),
  )
})

test('a scrolled item eases between the sizes of the slots it is between', () => {
  const keylines = getKeylines(640)
  const [first, second] = placeItems(20, keylines, 0.5)
  // Half gone past the leading edge, half way to small; the next half way from large.
  expect(first!.size).toBeCloseTo((keylines.large + keylines.small) / 2)
  expect(second!.size).toBeCloseTo(keylines.large)
  expect(second!.x).toBeCloseTo(keylines.stride / 2)
})

test('a row that fits takes no steps and keeps every item large', () => {
  const keylines = getKeylines(640)
  expect(getSteps(2, keylines)).toBe(0)
  expect(placeItems(2, keylines, 0).map(({ size }) => size)).toEqual([
    keylines.large,
    keylines.large,
  ])
})
