import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { defaultUrls, parseHash } from '../src/map/selection-url.ts'

test('reads the place a hash names, decoded, and nothing from a bad one', () => {
  assert.equal(parseHash('#nanjing'), 'nanjing')
  assert.equal(parseHash('#%E5%8D%97%E4%BA%AC'), '南京')
  assert.equal(parseHash(''), undefined)
  assert.equal(parseHash('#%E0%A4%A'), undefined)
})

test('knows the trip a path opens and the URLs of what the client fetches', () => {
  assert.equal(defaultUrls.getTripId('/trip/2026-yunnan/'), '2026-yunnan')
  assert.equal(defaultUrls.getTripId('/trip/2026-yunnan'), '2026-yunnan')
  assert.equal(defaultUrls.getTripId('/map/'), undefined)
  assert.equal(defaultUrls.getTripPath('a b'), '/trip/a%20b/')
  assert.equal(defaultUrls.getTripDataUrl('x'), '/trip/x/data.json')
  assert.equal(defaultUrls.getPlaceUrl('x'), '/map/place/x/')
})

test('knows the year or the month a path narrows the map to, and the path of one', () => {
  assert.deepEqual(defaultUrls.getPeriod('/map/2025/'), { month: undefined, year: 2025 })
  assert.deepEqual(defaultUrls.getPeriod('/map/2025/06/'), { month: 6, year: 2025 })
  assert.equal(defaultUrls.getPeriod('/map/2025/13/'), undefined)
  assert.equal(defaultUrls.getPeriod('/map/'), undefined)
  assert.equal(defaultUrls.getPeriod('/map/place/x/'), undefined)
  assert.equal(defaultUrls.getPeriodPath({ year: 2025 }), '/map/2025/')
  assert.equal(defaultUrls.getPeriodPath({ month: 6, year: 2025 }), '/map/2025/06/')
  assert.equal(defaultUrls.getMomentsUrl(2025), '/map/2025/moments/')
})
