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
