import assert from 'node:assert/strict'
import test from 'node:test'

import { parseMomentDocument } from '../../src/moment-content.ts'

test('parses a canonical Moment through one interface', () => {
  const moment = parseMomentDocument([
    '---',
    'publishedAt: "2026-07-15T10:03:04+08:00"',
    'occurredOn: "2026-07-14"',
    'location: hefei',
    'sourceUrl: "https://x.com/hyoban/status/123"',
    'media:',
    '  - type: image',
    '    file: "image-1.jpg"',
    '    alt: "Marina Bay at dusk"',
    '---',
    '',
    'A canonical moment.',
    '',
  ].join('\n'), { id: '2026/07/15-1003-note' })

  assert.deepEqual(moment, {
    hidden: false,
    id: '2026/07/15-1003-note',
    location: 'hefei',
    media: [{
      alt: 'Marina Bay at dusk',
      file: 'image-1.jpg',
      type: 'image',
    }],
    occurredOn: '2026-07-14',
    provenance: {
      url: 'https://x.com/hyoban/status/123',
    },
    publishedAt: new Date('2026-07-15T02:03:04.000Z'),
    text: 'A canonical moment.',
  })
})

test('rejects blank media descriptions on canonical-first Moments', () => {
  const document = [
    '---',
    'publishedAt: "2026-07-15T10:03:04+08:00"',
    'media:',
    '  - type: image',
    '    file: "image-1.jpg"',
    '    alt: ""',
    '---',
    '',
    'Missing a media description.',
  ].join('\n')

  assert.throws(
    () => parseMomentDocument(document, { id: '2026/07/15-1003-note' }),
    /Canonical Moment media requires alt text/,
  )
})

test('rejects Moment references to unknown locations', () => {
  const document = [
    '---',
    'publishedAt: "2026-07-15T10:03:04+08:00"',
    'location: nowhere',
    'media: []',
    '---',
    '',
    'An unknown location.',
  ].join('\n')

  assert.throws(
    () => parseMomentDocument(document, { id: '2026/07/15-1003-note' }),
    /Unknown calendar map location id/,
  )
})
