import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { basename, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const root = fileURLToPath(new URL('../../', import.meta.url))
const postsRoot = join(root, 'src/content/posts')
const assetPattern = /https:\/\/image\.hyoban\.cc\/posts\/([^)"'\s]+)/g
const semanticFilenamePattern = /^[a-z0-9]+(?:-[a-z0-9]+)*\.(?:avif|gif|jpe?g|png|webp)$/

test('uses semantic, post-scoped names for article assets', async () => {
  const files = (await readdir(postsRoot)).filter(file => file.endsWith('.md'))
  const invalid = []

  for (const file of files) {
    const document = await readFile(join(postsRoot, file), 'utf8')
    const postSlug = basename(file, '.md')

    for (const match of document.matchAll(assetPattern)) {
      const [assetPostSlug, ...pathSegments] = match[1].split('/')

      if (
        assetPostSlug !== postSlug
        || pathSegments.length !== 1
        || !semanticFilenamePattern.test(pathSegments[0])
      ) {
        invalid.push(match[0])
      }
    }
  }

  assert.deepEqual(invalid, [])
})
