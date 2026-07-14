import assert from 'node:assert/strict'
import test from 'node:test'

import { parseTelegramExport } from './telegram-export.mjs'

test('groups exported album media across adjacent seconds', () => {
  const result = parseTelegramExport(exportFixture([
    message({
      date_unixtime: '1783953482',
      id: 159,
      photo: 'chats/chat_001/photos/one.jpg',
      text: [
        'Read ',
        { href: 'https://example.com/story', text: 'the story', type: 'text_link' },
      ],
    }),
    message({
      date_unixtime: '1783953483',
      id: 160,
      photo: 'chats/chat_001/photos/two.jpg',
      text: '',
    }),
  ]), telegramSource)

  assert.equal(result.moments.length, 1)
  assert.equal(result.moments[0].id, '159')
  assert.equal(result.moments[0].publishedAt, '2026-07-13T22:38:02+08:00')
  assert.equal(result.moments[0].sourceUrl, 'https://telegram.me/hyoban_travel/159')
  assert.equal(result.moments[0].text, 'Read the story (https://example.com/story)')
  assert.deepEqual(result.moments[0].media, [
    { extension: '.jpg', sourcePath: 'chats/chat_001/photos/one.jpg', type: 'image' },
    { extension: '.jpg', sourcePath: 'chats/chat_001/photos/two.jpg', type: 'image' },
  ])
})

test('ignores forwarded messages, service events, and other authors', () => {
  const result = parseTelegramExport(exportFixture([
    {
      action: 'create_channel',
      actor_id: 'channel3981320482',
      date_unixtime: '1',
      id: 1,
      text: '',
      type: 'service',
    },
    message({ forwarded_from: 'Someone else', id: 2, text: 'Not mine' }),
    message({ from_id: 'user42', id: 3, text: 'Also not mine' }),
    message({ id: 4, text: 'Mine' }),
  ]), telegramSource)

  assert.deepEqual(result.moments.map(moment => moment.id), ['4'])
  assert.equal(result.skippedForwarded, 1)
  assert.equal(result.skippedService, 1)
  assert.equal(result.skippedOtherAuthors, 1)
  assert.deepEqual(result.ignoredIds, ['1', '2', '3'])
})

test('imports complete videos as mp4 with exported thumbnails', () => {
  const result = parseTelegramExport(exportFixture([
    message({
      file: 'chats/chat_001/video_files/clip.MOV',
      id: 12,
      media_type: 'video_file',
      mime_type: 'video/mp4',
      text: 'A video',
      thumbnail: 'chats/chat_001/video_files/clip.MOV_thumb.jpg',
    }),
  ]), telegramSource)

  assert.deepEqual(result.moments[0].media, [{
    extension: '.mp4',
    posterPath: 'chats/chat_001/video_files/clip.MOV_thumb.jpg',
    sourcePath: 'chats/chat_001/video_files/clip.MOV',
    type: 'video',
  }])
})

test('selects the configured channel and rejects exports without it', () => {
  const target = exportFixture([message({ id: 4, text: 'Mine' })]).chats.list[0]
  const other = {
    id: 42,
    messages: [message({ from_id: 'channel42', text: 'Not mine' })],
    name: 'Another channel',
    type: 'public_channel',
  }

  const result = parseTelegramExport({ chats: { list: [other, target] } }, telegramSource)

  assert.deepEqual(result.moments.map(moment => moment.id), ['4'])
  assert.throws(
    () => parseTelegramExport({ chats: { list: [other] } }, telegramSource),
    /does not contain hyoban_travel \(chat 3981320482\)/,
  )
})

const telegramSource = {
  channel: 'hyoban_travel',
  chatId: 3981320482,
}

function exportFixture(messages) {
  return {
    chats: {
      list: [{
        id: 3981320482,
        messages,
        name: 'Hyoban travel',
        type: 'public_channel',
      }],
    },
  }
}

function message(overrides) {
  return {
    date_unixtime: '1783953482',
    from_id: 'channel3981320482',
    id: 1,
    text: '',
    type: 'message',
    ...overrides,
  }
}
