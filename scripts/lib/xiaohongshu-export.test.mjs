import assert from 'node:assert/strict'
import test from 'node:test'

import {
  canonicalXiaohongshuSourceUrl,
  composeXiaohongshuText,
  detectXiaohongshuMediaType,
  extractXiaohongshuNoteId,
  parseXiaohongshuNoteFields,
  parseXiaohongshuProfileNotes,
  planXiaohongshuMedia,
  publishedAtFromXiaohongshuNoteId,
  validateXiaohongshuDownload,
} from './xiaohongshu-export.mjs'

const noteId = '6a5504ba0000000006031b50'
const signedUrl = `https://www.xiaohongshu.com/user/profile/64085a45000000000f01228d/${noteId}?xsec_token=token&xsec_source=pc_user`

test('parses signed public profile notes and rejects duplicate IDs', () => {
  const notes = parseXiaohongshuProfileNotes([
    {
      id: noteId,
      title: '2026 w19 三河古镇',
      type: 'normal',
      url: signedUrl,
    },
  ])

  assert.deepEqual(notes, [{
    id: noteId,
    signedUrl,
    title: '2026 w19 三河古镇',
    type: 'normal',
  }])
  assert.throws(
    () => parseXiaohongshuProfileNotes([
      { id: noteId, title: '', type: 'normal', url: signedUrl },
      { id: noteId, title: '', type: 'normal', url: signedUrl },
    ]),
    /Duplicate Xiaohongshu note ID/,
  )
  assert.throws(
    () => parseXiaohongshuProfileNotes([{
      id: noteId,
      title: '',
      type: 'normal',
      url: `https://www.xiaohongshu.com/explore/${noteId}?xsec_token=`,
    }]),
    /Missing signed public URL/,
  )
})

test('derives the exact +08:00 publication time from the note ID', () => {
  assert.equal(
    publishedAtFromXiaohongshuNoteId(noteId),
    '2026-07-13T23:31:06+08:00',
  )
  assert.throws(
    () => publishedAtFromXiaohongshuNoteId('not-a-note-id'),
    /Unsupported Xiaohongshu note ID/,
  )
})

test('parses note fields and composes a non-duplicated body', () => {
  const fields = parseXiaohongshuNoteFields([
    { field: 'title', value: '  三河古镇\u00a0' },
    { field: 'author', value: 'Hyoban' },
    { field: 'content', value: ' 今天路过三河古镇。\n\n ' },
  ])

  assert.deepEqual(fields, {
    author: 'Hyoban',
    content: '今天路过三河古镇。',
    title: '三河古镇',
  })
  assert.equal(
    composeXiaohongshuText(fields.title, fields.content),
    '三河古镇\n\n今天路过三河古镇。',
  )
  assert.equal(
    composeXiaohongshuText('三河古镇', '三河古镇\n今天路过。'),
    '三河古镇\n今天路过。',
  )
  assert.equal(
    composeXiaohongshuText('（无标题）', '只有正文'),
    '只有正文',
  )
})

test('canonicalizes and extracts Xiaohongshu note IDs without retaining tokens', () => {
  assert.equal(
    canonicalXiaohongshuSourceUrl(noteId),
    `https://www.xiaohongshu.com/explore/${noteId}`,
  )
  assert.equal(extractXiaohongshuNoteId(signedUrl), noteId)
  assert.equal(
    extractXiaohongshuNoteId(`https://www.xiaohongshu.com/explore/${noteId}`),
    noteId,
  )
  assert.equal(extractXiaohongshuNoteId('https://example.com'), undefined)
})

test('recognizes actual media bytes instead of misleading download extensions', () => {
  assert.deepEqual(
    detectXiaohongshuMediaType(Buffer.from('524946460000000057454250', 'hex')),
    { extension: '.webp', type: 'image' },
  )
  assert.deepEqual(
    detectXiaohongshuMediaType(Buffer.from('000000206674797069736F6D', 'hex')),
    { extension: '.mp4', type: 'video' },
  )
  assert.throws(
    () => detectXiaohongshuMediaType(Buffer.from('unknown')),
    /Unsupported Xiaohongshu media file/,
  )
})

test('uses the downloaded image as the poster for a video note', () => {
  const media = planXiaohongshuMedia('video', [
    { extension: '.webp', index: 2, sourcePath: '/stage/cover.jpg', type: 'image' },
    { extension: '.mp4', index: 1, sourcePath: '/stage/video.mp4', type: 'video' },
  ])

  assert.deepEqual(media, [{
    extension: '.mp4',
    posterExtension: '.webp',
    posterPath: '/stage/cover.jpg',
    sourcePath: '/stage/video.mp4',
    type: 'video',
  }])
})

test('rejects partial downloads and report-to-disk mismatches', () => {
  const downloaded = [
    { extension: '.webp', index: 1, sourcePath: '/stage/one.jpg', type: 'image' },
    { extension: '.webp', index: 2, sourcePath: '/stage/two.jpg', type: 'image' },
  ]
  const successfulOutput = [
    '[1/2] download progress',
    'Download complete: 2 downloaded',
    JSON.stringify([
      { index: 1, status: 'success', type: 'image' },
      { index: 2, status: 'success', type: 'image' },
    ], null, 2),
  ].join('\n')

  assert.doesNotThrow(() => validateXiaohongshuDownload(successfulOutput, downloaded))
  assert.throws(
    () => validateXiaohongshuDownload(JSON.stringify([
      { index: 1, status: 'success', type: 'image' },
      { index: 2, status: 'failed', type: 'image' },
    ]), downloaded),
    /download failed at index 2/,
  )
  assert.throws(
    () => validateXiaohongshuDownload(successfulOutput, downloaded.slice(0, 1)),
    /reported 2 media file\(s\), found 1 on disk/,
  )
})
