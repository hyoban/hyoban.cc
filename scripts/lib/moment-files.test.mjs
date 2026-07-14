import assert from 'node:assert/strict'
import test from 'node:test'

import {
  parseMomentOccurredOn,
  parseMomentSourceUrl,
  serializeMoment,
} from './moment-files.mjs'

test('serializes an occurrence date separately from the publication time', () => {
  const document = serializeMoment({
    media: [],
    occurredOn: '2026-07-11',
    publishedAt: '2026-07-13T22:38:02+08:00',
    sourceUrl: 'https://telegram.me/hyoban_travel/159',
    text: 'A delayed travel note.',
  })

  assert.match(
    document,
    /^---\npublishedAt: "2026-07-13T22:38:02\+08:00"\noccurredOn: "2026-07-11"\nsourceUrl:/,
  )
})

test('omits the occurrence date when none is provided', () => {
  const document = serializeMoment({
    media: [],
    publishedAt: '2026-07-05T15:41:45+08:00',
    sourceUrl: 'https://telegram.me/hyoban_travel/151',
    text: 'A same-day note.',
  })

  assert.doesNotMatch(document, /^occurredOn:/m)
})

test('reads an occurrence date from existing moment frontmatter', () => {
  const document = [
    '---',
    'publishedAt: "2026-07-13T22:38:02+08:00"',
    'occurredOn: "2026-07-11"',
    'media: []',
    '---',
    '',
    'occurredOn: 1999-01-01',
  ].join('\n')

  assert.equal(parseMomentOccurredOn(document), '2026-07-11')
})

test('reads a source URL only from moment frontmatter', () => {
  const document = [
    '---',
    'publishedAt: "2026-07-13T22:38:02+08:00"',
    'sourceUrl: "https://www.xiaohongshu.com/explore/6a5504ba0000000006031b50"',
    'media: []',
    '---',
    '',
    'sourceUrl: https://example.com/not-frontmatter',
  ].join('\n')

  assert.equal(
    parseMomentSourceUrl(document),
    'https://www.xiaohongshu.com/explore/6a5504ba0000000006031b50',
  )
})
