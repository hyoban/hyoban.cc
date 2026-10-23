import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { getCoverThumbnailUrl, getFrameSources, getResizedSources } from '../../src/asset-url.ts'

// An original the API kept is resized by Cloudflare Images to the widths the pipeline
// gives its own images, never wider than the original or 1920; a kept video's poster is
// its first frame from Media Transformations, at the same widths.

const original = (width, key = 'moments/2026/10/19-01-e2e-pier/e2e-pier.jpg') => ({
  key,
  contentType: 'image/jpeg',
  bytes: 1,
  etag: '0'.repeat(32),
  width,
  height: width,
  variants: [],
  original: true,
})

test('a large original comes in the three widths', () => {
  assert.deepEqual(getResizedSources(original(4032)), [
    {
      src: 'https://image.hyoban.cc/cdn-cgi/image/width=480,format=auto,onerror=redirect/moments/2026/10/19-01-e2e-pier/e2e-pier.jpg',
      width: 480,
    },
    {
      src: 'https://image.hyoban.cc/cdn-cgi/image/width=960,format=auto,onerror=redirect/moments/2026/10/19-01-e2e-pier/e2e-pier.jpg',
      width: 960,
    },
    {
      src: 'https://image.hyoban.cc/cdn-cgi/image/width=1920,format=auto,onerror=redirect/moments/2026/10/19-01-e2e-pier/e2e-pier.jpg',
      width: 1920,
    },
  ])
})

test('a small original stops at its own width, and keys stay encoded', () => {
  assert.deepEqual(
    getResizedSources(original(800, 'moments/a b/图.heic')).map(({ src, width }) => [
      src.slice(src.indexOf('/cdn-cgi/')),
      width,
    ]),
    [
      ['/cdn-cgi/image/width=480,format=auto,onerror=redirect/moments/a%20b/%E5%9B%BE.heic', 480],
      ['/cdn-cgi/image/width=800,format=auto,onerror=redirect/moments/a%20b/%E5%9B%BE.heic', 800],
    ],
  )
  assert.deepEqual(
    getResizedSources(original(480)).map(({ width }) => width),
    [480],
  )
})

test('a kept video’s poster is its first frame at each width', () => {
  assert.deepEqual(getFrameSources('moments/2026/10/19-01-e2e-pier/e2e-pier-video.mp4', 1080), [
    {
      src: 'https://image.hyoban.cc/cdn-cgi/media/mode=frame,time=0s,width=480,format=jpg/moments/2026/10/19-01-e2e-pier/e2e-pier-video.mp4',
      width: 480,
    },
    {
      src: 'https://image.hyoban.cc/cdn-cgi/media/mode=frame,time=0s,width=960,format=jpg/moments/2026/10/19-01-e2e-pier/e2e-pier-video.mp4',
      width: 960,
    },
    {
      src: 'https://image.hyoban.cc/cdn-cgi/media/mode=frame,time=0s,width=1080,format=jpg/moments/2026/10/19-01-e2e-pier/e2e-pier-video.mp4',
      width: 1080,
    },
  ])
})

test('a still taller than its tile comes cropped to it, a wider one whole', () => {
  const still = (width, height) => ({
    src: 'https://image.hyoban.cc/moments/2025/12/31-02-x/x.webp',
    width,
    height,
    sources: [{ src: 'https://image.hyoban.cc/moments/2025/12/31-02-x/x-480w.webp', width: 480 }],
  })

  assert.equal(
    getCoverThumbnailUrl(still(480, 2970), 320, 1),
    'https://image.hyoban.cc/cdn-cgi/image/width=320,height=320,fit=cover,format=auto,onerror=redirect/moments/2025/12/31-02-x/x-480w.webp',
  )
  assert.equal(
    getCoverThumbnailUrl(still(1920, 1440), 320, 1),
    'https://image.hyoban.cc/cdn-cgi/image/width=320,format=auto,onerror=redirect/moments/2025/12/31-02-x/x-480w.webp',
  )
  assert.equal(
    getCoverThumbnailUrl(still(1440, 1920), 320, 5 / 4),
    'https://image.hyoban.cc/cdn-cgi/image/width=320,height=400,fit=cover,format=auto,onerror=redirect/moments/2025/12/31-02-x/x-480w.webp',
  )
})
