import assert from 'node:assert/strict'
import test from 'node:test'

import { parseMomentDocument } from '../../src/moments/content.ts'

test('parses a canonical Moment through one interface', () => {
  const moment = parseMomentDocument([
    '---',
    'occurredAt: "2026-07-14T10:03:04+08:00"',
    'location: hefei',
    'media:',
    '  - type: image',
    '    file: "hefei-skyline.jpg"',
    '    alt: "Marina Bay at dusk"',
    '---',
    '',
    'A canonical moment.',
    '',
  ].join('\n'), { id: '2026/07/14-01-hefei-skyline' })

  assert.deepEqual(moment, {
    hidden: false,
    id: '2026/07/14-01-hefei-skyline',
    location: 'hefei',
    media: [{
      alt: 'Marina Bay at dusk',
      file: 'hefei-skyline.jpg',
      type: 'image',
    }],
    occurredAt: '2026-07-14T10:03:04+08:00',
    text: 'A canonical moment.',
  })
})

test('accepts a date-only occurrence and preserves imported blank descriptions', () => {
  const document = [
    '---',
    'occurredAt: "2026-07-14"',
    'media:',
    '  - type: image',
    '    file: "legacy-screenshot.jpg"',
    '    alt: ""',
    '---',
    '',
    'A date-only moment.',
  ].join('\n')
  const moment = parseMomentDocument(document, { id: '2026/07/14-02-date-only' })

  assert.equal(moment.occurredAt, '2026-07-14')
  assert.equal(moment.media[0].alt, '')
})

test('rejects Moment references to unknown locations', () => {
  const document = [
    '---',
    'occurredAt: "2026-07-15T10:03:04+08:00"',
    'location: nowhere',
    'media: []',
    '---',
    '',
    'An unknown location.',
  ].join('\n')

  assert.throws(
    () => parseMomentDocument(document, { id: '2026/07/15-01-note' }),
    /Unknown calendar map location id/,
  )
})

test('rejects occurrence timestamps without an explicit offset', () => {
  const document = [
    '---',
    'occurredAt: "2026-07-15T10:03:04"',
    'media: []',
    '---',
    '',
    'An ambiguous timestamp.',
  ].join('\n')

  assert.throws(
    () => parseMomentDocument(document, { id: '2026/07/15-01-note' }),
    /Invalid ISO datetime/,
  )
})
