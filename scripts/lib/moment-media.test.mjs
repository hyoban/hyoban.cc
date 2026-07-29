import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import { parseMomentDocument } from '../../src/moment-content.ts'

const root = fileURLToPath(new URL('../../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')
const manifest = JSON.parse(
  await readFile(join(root, 'src/data/moment-media.generated.json'), 'utf8'),
)

test('has remote image metadata for every Moment image and video poster', async () => {
  const files = (await readdir(contentRoot, { recursive: true }))
    .filter(file => file.endsWith('index.md'))
  const missing = []

  for (const file of files) {
    const document = await readFile(join(contentRoot, file), 'utf8')
    const id = file.replace(/\/index\.md$/, '')
    const moment = parseMomentDocument(document, { id })

    for (const media of moment.media) {
      const imageFiles = media.type === 'image'
        ? [media.file]
        : media.poster
          ? [media.poster]
          : []

      for (const imageFile of imageFiles) {
        const key = `moments/${id}/${imageFile}`

        if (!manifest[key]) {
          missing.push(key)
        }
      }
    }
  }

  assert.deepEqual(missing, [])
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
