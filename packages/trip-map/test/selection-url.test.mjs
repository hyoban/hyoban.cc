import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { defaultUrls, parseHash } from '../src/map/selection-url.ts'

test('reads the place an old hash names, decoded, and nothing from a bad one', () => {
  assert.equal(parseHash('#nanjing'), 'nanjing')
  assert.equal(parseHash('#%E5%8D%97%E4%BA%AC'), '南京')
  assert.equal(parseHash(''), undefined)
  assert.equal(parseHash('#%E0%A4%A'), undefined)
})

test('knows the trip a path plays, from which card, and the trip it picks', () => {
  assert.equal(defaultUrls.getTripId('/trip/2026-yunnan/play/'), '2026-yunnan')
  assert.equal(defaultUrls.getTripId('/trip/2026-yunnan/play/01-02-lake/'), '2026-yunnan')
  assert.equal(defaultUrls.getTripStop('/trip/2026-yunnan/play/01-02-lake/'), '01-02-lake')
  assert.equal(defaultUrls.getTripStop('/trip/2026-yunnan/play/'), undefined)
  assert.equal(defaultUrls.getTripId('/trip/2026-yunnan/'), undefined)
  assert.equal(defaultUrls.getPickedTripId('/trip/2026-yunnan/'), '2026-yunnan')
  assert.equal(defaultUrls.getPickedTripId('/trip/'), undefined)
  assert.equal(defaultUrls.getPickedTripId('/trip/2026-yunnan/play/'), undefined)
  assert.equal(defaultUrls.getTripPath('a b'), '/trip/a%20b/play/')
  assert.equal(defaultUrls.getTripPath('x', '01-02-lake'), '/trip/x/play/01-02-lake/')
  assert.equal(defaultUrls.getPickedTripPath('x'), '/trip/x/')
  assert.equal(defaultUrls.getTripDataUrl('x'), '/trip/x/data.json')
})

test('knows the period and the place a map path opens, and the path of each', () => {
  assert.deepEqual(defaultUrls.getPeriod('/map/2025/'), { month: undefined, year: 2025 })
  assert.deepEqual(defaultUrls.getPeriod('/map/2025/06/'), { month: 6, year: 2025 })
  assert.deepEqual(defaultUrls.getPeriod('/map/2025/06/place/x/'), { month: 6, year: 2025 })
  assert.equal(defaultUrls.getPeriod('/map/2025/13/'), undefined)
  assert.equal(defaultUrls.getPeriod('/map/'), undefined)
  assert.equal(defaultUrls.getPlaceId('/map/place/nanjing/'), 'nanjing')
  assert.equal(defaultUrls.getPlaceId('/map/2025/place/nanjing/'), 'nanjing')
  assert.equal(defaultUrls.getPlaceId('/map/2025/'), undefined)
  assert.equal(defaultUrls.isMapPath('/map/'), true)
  assert.equal(defaultUrls.isMapPath('/map/2025/06/place/x/'), true)
  assert.equal(defaultUrls.isMapPath('/map/place/x/moments/'), false)
  assert.equal(defaultUrls.isMapPath('/trip/'), false)
  assert.equal(defaultUrls.getMapPath(), '/map/')
  assert.equal(defaultUrls.getMapPath({ year: 2025 }), '/map/2025/')
  assert.equal(defaultUrls.getMapPath({ month: 6, year: 2025 }, 'x'), '/map/2025/06/place/x/')
  assert.equal(defaultUrls.getMapPath(undefined, 'x'), '/map/place/x/')
  assert.equal(defaultUrls.getPlaceUrl('x'), '/map/place/x/moments/')
  assert.equal(defaultUrls.getMomentsUrl(2025), '/map/2025/moments/')
})

test('leads to a trip by its id or by its year and the rest, either way', () => {
  assert.equal(defaultUrls.getPickedTripId('/trip/2026/yunnan/'), '2026-yunnan')
  assert.equal(defaultUrls.getTripId('/trip/2026/yunnan/play/'), '2026-yunnan')
  assert.equal(defaultUrls.getTripStop('/trip/2026/yunnan/play/01-02-lake/'), '01-02-lake')
  assert.equal(defaultUrls.getPickedTripId('/trip/2026/'), '2026')
  assert.equal(defaultUrls.isNestedTripPath('/trip/2026/yunnan/play/'), true)
  assert.equal(defaultUrls.isNestedTripPath('/trip/2026-yunnan/'), false)
  assert.equal(defaultUrls.getPickedTripPath('2026-yunnan', true), '/trip/2026/yunnan/')
  assert.equal(defaultUrls.getTripPath('2026-yunnan', 'x', true), '/trip/2026/yunnan/play/x/')
  assert.equal(
    defaultUrls.getTripPath('code-and-travel', undefined, true),
    '/trip/code-and-travel/play/',
  )
})
