import assert from 'node:assert/strict'
import { readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'vite-plus/test'

import { readMomentRecords } from '../../src/data/store.ts'

const root = fileURLToPath(new URL('../../', import.meta.url))

test('publishes every Moment with complete metadata for each media file', async () => {
  for (const { record } of readMomentRecords()) {
    assert.ok(record.published, `Moment ${record.uid} is not published`)

    for (const media of record.media) {
      const { published } = media
      assert.ok(published, `Moment ${record.uid} has unpublished media ${media.uid}`)
      assert.doesNotMatch(published.key, /\/(?:image|video)-\d/i)
      assert.ok(published.bytes > 0)

      if (media.type === 'video') {
        assert.match(published.contentType, /^video\//)

        if (published.poster) {
          assert.match(published.poster.contentType, /^image\//)
          assert.ok(published.poster.width > 0 && published.poster.height > 0)
        }
      } else {
        assert.match(published.contentType, /^image\//)
        assert.ok(published.width > 0 && published.height > 0)

        for (const variant of published.variants) {
          assert.ok(variant.width < published.width, `Variant ${variant.key} isn't smaller`)
          assert.ok(variant.bytes > 0)
        }
      }

      if (media.type === 'live-photo') {
        assert.ok(published.video.bytes > 0)
        assert.match(published.video.contentType, /^video\//)
      }
    }
  }
})

test('keeps binary site media out of the repository', async () => {
  const roots = [join(root, 'data'), join(root, 'src/assets'), join(root, 'public')]
  const mediaFiles = (
    await Promise.all(
      roots.map(async (directory) => {
        try {
          return (await readdir(directory, { recursive: true }))
            .filter((file) => /\.(?:avif|gif|jpe?g|mov|mp4|png|webm|webp)$/i.test(file))
            .map((file) => join(directory, file))
        } catch (error) {
          if (error.code === 'ENOENT') return []

          throw error
        }
      }),
    )
  ).flat()

  assert.deepEqual(mediaFiles, [])
})
