import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createOpenCliPublicationAdapters,
  PublicationOutcomeUnknownError,
} from './opencli-publication-adapters.mjs'

test('publishes a Telegram adaptation through the project OpenCLI adapter', async () => {
  const calls = []
  const adapters = createOpenCliPublicationAdapters({
    runOpenCli: async (args) => {
      calls.push(args)
      return [{
        id: '163',
        status: 'success',
        url: 'https://t.me/hyoban_travel/163',
      }]
    },
  })
  const payload = adapters.telegram.adapt({
    media: [
      {
        alt: 'A train entering the station.',
        sourcePath: '/moments/video-1.mp4',
        type: 'video',
      },
    ],
    text: 'A canonical moment.',
  })

  const result = await adapters.telegram.publish(payload)

  assert.deepEqual(payload, {
    media: ['/moments/video-1.mp4'],
    text: 'A canonical moment.\n\nMedia descriptions:\n1. A train entering the station.',
  })
  assert.deepEqual(calls, [[
    'telegram',
    'publish',
    payload.text,
    '--media',
    '/moments/video-1.mp4',
    '--channel',
    'hyoban_travel',
    '--peer',
    '-1003981320482',
    '--execute',
    '--trace',
    'retain-on-failure',
    '-f',
    'json',
  ]])
  assert.deepEqual(result, {
    externalId: '163',
    url: 'https://t.me/hyoban_travel/163',
  })
})

test('rejects Telegram adaptations that cannot preserve the complete Moment', () => {
  const adapters = createOpenCliPublicationAdapters({ runOpenCli: async () => ({}) })

  assert.throws(
    () => adapters.telegram.adapt({
      media: Array.from({ length: 11 }, (_, index) => ({
        alt: `Image ${index + 1}`,
        sourcePath: `/moments/image-${index + 1}.jpg`,
        type: 'image',
      })),
      text: 'Too many attachments.',
    }),
    /Telegram supports at most 10 media attachments/,
  )
  assert.throws(
    () => adapters.telegram.adapt({
      media: [{ alt: '', sourcePath: '/moments/image-1.jpg', type: 'image' }],
      text: 'x'.repeat(1_025),
    }),
    /Telegram media captions support at most 1024 characters/,
  )
})

test('publishes an X adaptation through OpenCLI and returns a receipt identity', async () => {
  const calls = []
  const adapters = createOpenCliPublicationAdapters({
    runOpenCli: async (args) => {
      calls.push(args)
      return {
        id: '99',
        status: 'success',
        url: 'https://x.com/hyoban/status/99',
      }
    },
  })
  const payload = adapters.x.adapt({
    media: [
      { sourcePath: '/moments/image-1.jpg', type: 'image' },
      { sourcePath: '/moments/image-2.jpg', type: 'image' },
    ],
    text: 'A canonical moment.',
  })

  const result = await adapters.x.publish(payload)

  assert.deepEqual(calls, [[
    'twitter',
    'post',
    'A canonical moment.',
    '--images',
    '/moments/image-1.jpg,/moments/image-2.jpg',
    '--trace',
    'retain-on-failure',
    '-f',
    'json',
  ]])
  assert.deepEqual(result, {
    externalId: '99',
    url: 'https://x.com/hyoban/status/99',
  })
})

test('rejects X adaptations that would discard media', () => {
  const adapters = createOpenCliPublicationAdapters({ runOpenCli: async () => ({}) })

  assert.throws(
    () => adapters.x.adapt({
      media: Array.from({ length: 5 }, (_, index) => ({
        sourcePath: `/moments/image-${index + 1}.jpg`,
        type: 'image',
      })),
      text: 'Too many images.',
    }),
    /X supports at most 4 images/,
  )
  assert.throws(
    () => adapters.x.adapt({
      media: [{ sourcePath: '/moments/video.mp4', type: 'video' }],
      text: 'A video.',
    }),
    /X video Publication is not supported/,
  )
})

test('publishes a Xiaohongshu adaptation and resolves its external identity', async () => {
  const calls = []
  const adapters = createOpenCliPublicationAdapters({
    runOpenCli: async (args) => {
      calls.push(args)

      if (args[1] === 'publish') {
        return [{ detail: 'published', status: '✅ 发布成功' }]
      }

      return [{
        id: 'abc123',
        title: 'A canonical moment.',
        url: 'https://www.xiaohongshu.com/explore/abc123',
      }]
    },
  })
  const payload = adapters.xiaohongshu.adapt({
    media: [{ sourcePath: '/moments/image-1.jpg', type: 'image' }],
    text: 'A canonical moment.\n\nWith the full body preserved.',
  })

  const result = await adapters.xiaohongshu.publish(payload)

  assert.deepEqual(calls, [
    [
      'xiaohongshu',
      'publish',
      payload.content,
      '--title',
      'A canonical moment.',
      '--images',
      '/moments/image-1.jpg',
      '--trace',
      'retain-on-failure',
      '-f',
      'json',
    ],
    [
      'xiaohongshu',
      'creator-notes',
      '--limit',
      '10',
      '--trace',
      'retain-on-failure',
      '-f',
      'json',
    ],
  ])
  assert.deepEqual(result, {
    externalId: 'abc123',
    url: 'https://www.xiaohongshu.com/explore/abc123',
  })
})

test('rejects Xiaohongshu adaptations that would discard media', () => {
  const adapters = createOpenCliPublicationAdapters({ runOpenCli: async () => ({}) })

  assert.throws(
    () => adapters.xiaohongshu.adapt({
      media: Array.from({ length: 10 }, (_, index) => ({
        sourcePath: `/moments/image-${index + 1}.jpg`,
        type: 'image',
      })),
      text: 'Too many images.',
    }),
    /Xiaohongshu supports at most 9 images/,
  )
  assert.throws(
    () => adapters.xiaohongshu.adapt({
      media: [{ sourcePath: '/moments/video.mp4', type: 'video' }],
      text: 'A video.',
    }),
    /Xiaohongshu video Publication is not supported/,
  )
})

test('marks an unidentifiable Xiaohongshu write as an unknown outcome', async () => {
  const adapters = createOpenCliPublicationAdapters({
    runOpenCli: async (args) => args[1] === 'publish'
      ? [{ detail: 'published', status: '✅ 发布成功' }]
      : [],
  })
  const payload = adapters.xiaohongshu.adapt({ media: [], text: 'A canonical moment.' })

  await assert.rejects(
    adapters.xiaohongshu.publish(payload),
    error => error instanceof PublicationOutcomeUnknownError
      && /could not be identified/.test(error.message),
  )
})
