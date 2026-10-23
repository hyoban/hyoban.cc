import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'vite-plus/test'

import { assertPublicZeekrCapture, redactZeekrCapture } from './zeekr-privacy.mjs'

const directory = new URL('../../data/zeekr/', import.meta.url)
const entries = JSON.parse(readFileSync(new URL('imports.json', directory), 'utf8'))
const sources = entries.map((entry) =>
  JSON.parse(readFileSync(new URL(entry.file, directory), 'utf8')),
)

test('every committed source is explicitly redacted and contains only public statistical fields', () => {
  for (const source of sources) assert.doesNotThrow(() => assertPublicZeekrCapture(source))
})

test('removes identities, access links, vehicle state and unrelated phone pages', () => {
  const raw = structuredClone(sources[0])
  raw.car = { location: 'private address', name: 'private name' }
  raw.energy = { battery_percent: 70 }
  raw.trips.months[0].share_url = 'https://example.test/?key=secret'
  Object.assign(raw.trips.months[0].share_data, {
    userPhone: '155****1234',
    avatar: 'https://example.test/avatar',
    carPicUrl: 'https://example.test/car',
    vin: 'private-vin',
  })
  raw.raw_screens = [
    { sequence: 1, raw_text: ['爱车', 'private address'] },
    { sequence: 2, raw_text: ['行程统计', 'drive.abcdef', '3516', 'km'] },
  ]
  const clean = redactZeekrCapture(raw)
  assert.equal(clean.car, undefined)
  assert.equal(clean.energy, undefined)
  assert.equal(clean.trips.months[0].share_url, undefined)
  for (const key of ['userPhone', 'avatar', 'carPicUrl', 'vin'])
    assert.equal(clean.trips.months[0].share_data[key], undefined)
  assert.deepEqual(clean.raw_screens, [{ sequence: 2, raw_text: ['行程统计', '3516', 'km'] }])
  assert.deepEqual(clean.trips.months[0].daily, sources[0].trips.months[0].daily)
  assert.deepEqual(clean.charging, sources[0].charging)
  assert.throws(() => assertPublicZeekrCapture(raw), /non-public/)
})

test('rejects credentials or free-form identity text inserted into statistical fields', () => {
  for (const value of [
    'https://example.test/?key=secret',
    '155****1234',
    '13800138000',
    'private address',
  ]) {
    const raw = structuredClone(sources[0])
    raw.trips.months[0].share_data.yearMonth = value
    assert.throws(() => redactZeekrCapture(raw), /text/)
  }
})

test('does not accept unredacted files even when their numbers are valid', () => {
  const raw = structuredClone(sources[1])
  delete raw.redacted
  assert.throws(() => assertPublicZeekrCapture(raw), /explicitly redacted/)
})
