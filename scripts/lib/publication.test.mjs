import assert from 'node:assert/strict'
import test from 'node:test'

import {
  executePublication,
  previewPublication,
  PublicationOutcomeUnknownError,
} from './publication.mjs'

const moment = {
  hidden: false,
  id: '2026/07/15-1003-note',
  media: [],
  publishedAt: new Date('2026-07-15T02:03:04.000Z'),
  text: 'A canonical moment.',
}

test('previews every Platform without performing external writes', async () => {
  const published = []
  const adapters = Object.fromEntries(
    ['telegram', 'xiaohongshu', 'x'].map(platform => [
      platform,
      {
        adapt: source => ({ platform, text: source.text }),
        publish: async (payload) => published.push(payload),
      },
    ]),
  )

  const preview = await previewPublication({ adapters, moment, receipts: {} })

  assert.equal(preview.executable, true)
  assert.deepEqual(preview.platforms, [
    { payload: { platform: 'telegram', text: moment.text }, platform: 'telegram', status: 'ready' },
    { payload: { platform: 'xiaohongshu', text: moment.text }, platform: 'xiaohongshu', status: 'ready' },
    { payload: { platform: 'x', text: moment.text }, platform: 'x', status: 'ready' },
  ])
  assert.deepEqual(published, [])
})

test('rejects Hidden Moments before adapting any Platform payload', async () => {
  const adapters = Object.fromEntries(
    ['telegram', 'xiaohongshu', 'x'].map(platform => [
      platform,
      {
        adapt: () => {
          throw new Error('adapter should not run')
        },
      },
    ]),
  )

  await assert.rejects(
    previewPublication({
      adapters,
      moment: { ...moment, hidden: true },
      receipts: {},
    }),
    /Hidden Moments cannot be published/,
  )
})

test('reports every invalid Platform adaptation without executing any publish call', async () => {
  const published = []
  const adapters = {
    telegram: {
      adapt: source => ({ text: source.text }),
      publish: payload => published.push(payload),
    },
    xiaohongshu: {
      adapt: () => {
        throw new Error('Xiaohongshu supports at most 9 images.')
      },
      publish: payload => published.push(payload),
    },
    x: {
      adapt: source => ({ text: source.text }),
      publish: payload => published.push(payload),
    },
  }

  const preview = await previewPublication({ adapters, moment, receipts: {} })

  assert.equal(preview.executable, false)
  assert.deepEqual(preview.platforms.map(item => ({
    error: item.error,
    platform: item.platform,
    status: item.status,
  })), [
    { error: undefined, platform: 'telegram', status: 'ready' },
    { error: 'Xiaohongshu supports at most 9 images.', platform: 'xiaohongshu', status: 'invalid' },
    { error: undefined, platform: 'x', status: 'ready' },
  ])
  assert.deepEqual(published, [])
})

test('treats a successful Publication Receipt as terminal and skips adaptation', async () => {
  const telegramReceipt = {
    externalId: '42',
    publishedAt: '2026-07-15T02:10:00.000Z',
    status: 'published',
    url: 'https://t.me/hyoban_travel/42',
  }
  const adapters = {
    telegram: {
      adapt: () => {
        throw new Error('published Platform should not adapt again')
      },
    },
    xiaohongshu: {
      adapt: source => ({ text: source.text }),
    },
    x: {
      adapt: source => ({ text: source.text }),
    },
  }

  const preview = await previewPublication({
    adapters,
    moment,
    receipts: { telegram: telegramReceipt },
  })

  assert.equal(preview.executable, true)
  assert.deepEqual(preview.platforms[0], {
    platform: 'telegram',
    receipt: telegramReceipt,
    status: 'published',
  })
})

test('keeps successful Publications and records failures for independent retry', async () => {
  const written = []
  const adapters = {
    telegram: {
      adapt: source => ({ text: source.text }),
      publish: async () => ({
        externalId: '42',
        url: 'https://t.me/hyoban_travel/42',
      }),
    },
    xiaohongshu: {
      adapt: source => ({ text: source.text }),
      publish: async () => {
        throw new Error('Xiaohongshu session expired.')
      },
    },
    x: {
      adapt: source => ({ text: source.text }),
      publish: async () => ({
        externalId: '99',
        url: 'https://x.com/hyoban/status/99',
      }),
    },
  }
  const preview = await previewPublication({ adapters, moment, receipts: {} })

  const result = await executePublication({
    adapters,
    now: () => new Date('2026-07-15T02:10:00.000Z'),
    preview,
    writeReceipt: async (platform, receipt) => written.push({ platform, receipt }),
  })

  assert.equal(result.successful, false)
  assert.deepEqual(result.platforms.map(item => item.status), [
    'published',
    'failed',
    'published',
  ])
  assert.deepEqual(written, [
    {
      platform: 'telegram',
      receipt: {
        externalId: '42',
        publishedAt: '2026-07-15T02:10:00.000Z',
        status: 'published',
        url: 'https://t.me/hyoban_travel/42',
      },
    },
    {
      platform: 'xiaohongshu',
      receipt: {
        attemptedAt: '2026-07-15T02:10:00.000Z',
        error: 'Xiaohongshu session expired.',
        status: 'failed',
      },
    },
    {
      platform: 'x',
      receipt: {
        externalId: '99',
        publishedAt: '2026-07-15T02:10:00.000Z',
        status: 'published',
        url: 'https://x.com/hyoban/status/99',
      },
    },
  ])
})

test('records an unknown write outcome as terminal to prevent duplicate retries', async () => {
  const written = []
  const adapters = {
    telegram: {
      adapt: source => ({ text: source.text }),
      publish: async () => {
        throw new PublicationOutcomeUnknownError('Telegram did not expose the new message id.')
      },
    },
  }
  const preview = await previewPublication({
    adapters,
    moment,
    receipts: {},
    targets: ['telegram'],
  })

  const result = await executePublication({
    adapters,
    now: () => new Date('2026-07-15T02:10:00.000Z'),
    preview,
    writeReceipt: async (platform, receipt) => written.push({ platform, receipt }),
  })

  assert.equal(result.successful, false)
  assert.deepEqual(written, [{
    platform: 'telegram',
    receipt: {
      attemptedAt: '2026-07-15T02:10:00.000Z',
      error: 'Telegram did not expose the new message id.',
      status: 'unknown',
    },
  }])

  const retryPreview = await previewPublication({
    adapters: {
      telegram: {
        adapt: () => {
          throw new Error('unknown Platform should require manual reconciliation')
        },
      },
    },
    moment,
    receipts: { telegram: written[0].receipt },
    targets: ['telegram'],
  })

  assert.deepEqual(retryPreview.platforms, [{
    platform: 'telegram',
    receipt: written[0].receipt,
    status: 'unknown',
  }])
})
