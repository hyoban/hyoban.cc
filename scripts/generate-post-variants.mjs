import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { basename, extname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

import sharp from 'sharp'

import { uploadR2Object } from './lib/r2-assets.mjs'

// Article images are linked straight from R2. This makes webp copies sized for the
// 40rem column (1x and 2x) and records them in src/data/post-images.json, which
// src/post-images.mjs reads to give each <img> a srcset and its intrinsic size.
const VARIANT_WIDTHS = [640, 960, 1280]

const root = fileURLToPath(new URL('../', import.meta.url))
const postsRoot = join(root, 'src/content/posts')
const manifestPath = join(root, 'src/data/post-images.json')
const config = JSON.parse(await readFile(join(root, 'src/data/asset-config.json'), 'utf8'))
const assetPattern = new RegExp(`${escapeRegExp(config.origin)}/(posts/[^)"'\\s]+)`, 'g')
const manifest = JSON.parse(await readFile(manifestPath, 'utf8').catch(() => '{}'))
const temporaryPath = await mkdtemp(join(tmpdir(), 'hyoban-post-variants-'))

try {
  const keys = new Set()

  for (const file of await readdir(postsRoot)) {
    if (file.endsWith('.md')) {
      const document = await readFile(join(postsRoot, file), 'utf8')

      for (const match of document.matchAll(assetPattern)) {
        keys.add(match[1])
      }
    }
  }

  const pending = [...keys].filter((key) => !manifest[key])

  for (const key of pending) {
    const response = await fetch(`${config.origin}/${key}`)

    if (!response.ok) {
      throw new Error(`Unable to download ${key}: ${response.status}`)
    }

    const source = Buffer.from(await response.arrayBuffer())
    const animated = extname(key).toLowerCase() === '.gif'
    const metadata = await sharp(source, { animated }).metadata()
    // An animated image reports the height of every frame stacked together.
    const height = metadata.pageHeight ?? metadata.height
    const widths = animated
      ? [Math.min(metadata.width, VARIANT_WIDTHS.at(-1))]
      : VARIANT_WIDTHS.filter((width) => width < metadata.width)

    if (widths.length === 0) {
      widths.push(metadata.width)
    }

    const stem = basename(key, extname(key))
    const variants = []

    for (const width of widths) {
      const file = join(temporaryPath, `${stem}-${width}w.webp`)
      await sharp(source, { animated })
        .rotate()
        .resize({ width, withoutEnlargement: true })
        .webp({ effort: 6, quality: animated ? 75 : 80 })
        .toFile(file)
      variants.push({ file, width })
    }

    const largest = await readFile(variants.at(-1).file)

    // Keep the original when re-encoding doesn't help, which happens with some
    // small screenshots and GIFs (a GIF has only the one variant to compare).
    if (
      largest.byteLength >= source.byteLength &&
      (animated || variants.at(-1).width === metadata.width)
    ) {
      manifest[key] = { height, variants: [], width: metadata.width }
      console.log(`kept original: ${key}`)
      continue
    }

    for (const variant of variants) {
      const variantKey = `${key.slice(0, key.lastIndexOf('/'))}/${basename(variant.file)}`
      const result = await uploadR2Object({
        bucket: config.bucket,
        file: variant.file,
        key: variantKey,
        origin: config.origin,
        root,
      })
      variant.key = variantKey
      console.log(`${result.action}: ${result.url}`)
    }

    manifest[key] = {
      height,
      variants: variants.map((variant) => ({ key: variant.key, width: variant.width })),
      width: metadata.width,
    }
  }

  const sorted = Object.fromEntries(
    Object.entries(manifest).sort(([first], [second]) => first.localeCompare(second)),
  )
  await writeFile(manifestPath, `${JSON.stringify(sorted, null, 2)}\n`)
  console.log(`Post images complete: ${pending.length} new, ${keys.size} total.`)
} finally {
  await rm(temporaryPath, { force: true, recursive: true })
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
