import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import { parseMomentDocument } from '../../src/moments/content.ts'

const root = fileURLToPath(new URL('../../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')

test('keeps complete colocated metadata for every semantic Moment asset', async () => {
  const files = (await readdir(contentRoot, { recursive: true }))
    .filter(file => file.endsWith('index.md'))

  for (const file of files) {
    const document = await readFile(join(contentRoot, file), 'utf8')
    const id = file.replace(/\/index\.md$/, '')
    const moment = parseMomentDocument(document, { id })
    const assets = JSON.parse(
      await readFile(join(contentRoot, id, 'assets.json'), 'utf8'),
    )
    const referencedFiles = new Set()

    for (const media of moment.media) {
      referencedFiles.add(media.file)
      assert.doesNotMatch(media.file, /^(?:image|video)-\d/i)
      assert.ok(assets[media.file], `Missing metadata for moments/${id}/${media.file}`)
      assert.ok(assets[media.file].bytes > 0)
      assert.match(
        assets[media.file].contentType,
        media.type === 'image' ? /^image\// : /^video\//,
      )

      if (media.type === 'image') {
        assert.ok(assets[media.file].width > 0)
        assert.ok(assets[media.file].height > 0)
      }

      if (media.poster) {
        referencedFiles.add(media.poster)
        assert.ok(assets[media.poster], `Missing metadata for moments/${id}/${media.poster}`)
        assert.match(assets[media.poster].contentType, /^image\//)
        assert.ok(assets[media.poster].width > 0)
        assert.ok(assets[media.poster].height > 0)
      }
    }

    assert.match(id, /^\d{4}\/\d{2}\/\d{2}-\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*$/)
    assert.deepEqual(Object.keys(assets).sort(), [...referencedFiles].sort())
  }
})

test('keeps binary site media out of the repository', async () => {
  const roots = [
    contentRoot,
    join(root, 'src/assets'),
    join(root, 'public'),
  ]
  const mediaFiles = (
    await Promise.all(roots.map(async directory => (
      (await readdir(directory, { recursive: true }))
        .filter(file => /\.(?:avif|gif|jpe?g|mov|mp4|png|webm|webp)$/i.test(file))
        .map(file => join(directory, file))
    )))
  ).flat()

  assert.deepEqual(mediaFiles, [])
})
