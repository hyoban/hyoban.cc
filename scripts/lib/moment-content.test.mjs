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

test('keeps image and video fields mutually exclusive', () => {
  const imageWithPoster = [
    '---',
    'occurredAt: "2026-07-15"',
    'media:',
    '  - type: image',
    '    file: "lake.jpg"',
    '    poster: "lake-poster.jpg"',
    '    alt: "A lake"',
    '---',
  ].join('\n')

  assert.throws(
    () => parseMomentDocument(imageWithPoster, { id: '2026/07/15-01-lake' }),
    /Unrecognized key/,
  )
})

test('rejects unsafe or type-mismatched media filenames', () => {
  const videoWithImageFile = [
    '---',
    'occurredAt: "2026-07-15"',
    'media:',
    '  - type: video',
    '    file: "../clip.jpg"',
    '    alt: "A clip"',
    '---',
  ].join('\n')

  assert.throws(
    () => parseMomentDocument(videoWithImageFile, { id: '2026/07/15-01-clip' }),
    /safe kebab-case semantic video filename/,
  )
})
