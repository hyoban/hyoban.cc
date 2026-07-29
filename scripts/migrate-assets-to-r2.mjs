import { readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

import sharp from 'sharp'

import { getContentType, uploadR2Object } from './lib/r2-assets.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const configPath = join(root, 'src/data/asset-config.json')
const manifestPath = join(root, 'src/data/moment-media.generated.json')
const config = JSON.parse(await readFile(configPath, 'utf8'))
const shouldUpload = process.argv.includes('--upload')
const shouldRemoveLocal = process.argv.includes('--remove-local')

if (shouldRemoveLocal && !shouldUpload) {
  throw new Error('--remove-local requires --upload')
}

const sources = [
  {
    directory: join(root, 'src/content/moments'),
    keyPrefix: 'moments',
  },
  {
    directory: join(root, 'src/assets/images/posts'),
    keyPrefix: 'posts',
  },
  {
    directory: join(root, 'public'),
    keyPrefix: 'site',
  },
]
const assets = (
  await Promise.all(sources.map(collectSourceAssets))
).flat().sort((first, second) => first.key.localeCompare(second.key))

assertUniqueKeys(assets)

const momentImageMetadata = await readJsonOrDefault(manifestPath, {})

for (const asset of assets) {
  if (!asset.key.startsWith('moments/') || !getContentType(asset.file).startsWith('image/')) {
    continue
  }

  const metadata = await sharp(asset.file).metadata()

  if (!metadata.width || !metadata.height) {
    throw new Error(`Unable to read image dimensions: ${asset.file}`)
  }

  momentImageMetadata[asset.key] = {
    height: metadata.height,
    width: metadata.width,
  }
}

await writeFile(
  manifestPath,
  `${JSON.stringify(momentImageMetadata, null, 2)}\n`,
)

if (!shouldUpload) {
  console.log(
    `Verified metadata for ${Object.keys(momentImageMetadata).length} Moment image(s). `
    + `Run with --upload to migrate ${assets.length} asset(s) to R2.`,
  )
  process.exit()
}

const stats = { reused: 0, uploaded: 0 }
const completed = new Set()

await runConcurrent(assets, 6, async (asset, index) => {
  const result = await uploadR2Object({
    bucket: config.bucket,
    file: asset.file,
    key: asset.key,
    origin: config.origin,
    root,
  })

  stats[result.action] += 1
  completed.add(asset.file)

  if ((index + 1) % 25 === 0 || index === assets.length - 1) {
    console.log(`Verified ${index + 1}/${assets.length} asset(s).`)
  }
})

if (completed.size !== assets.length) {
  throw new Error(`Only ${completed.size}/${assets.length} assets were verified`)
}

if (shouldRemoveLocal) {
  await Promise.all([...completed].map(file => rm(file)))
  await removeEmptyDirectories(sources.map(source => source.directory))
}

console.log(
  `R2 migration complete: ${stats.uploaded} uploaded, ${stats.reused} reused`
  + `${shouldRemoveLocal ? `, ${completed.size} local files removed` : ''}.`,
)

async function collectSourceAssets(source) {
  const files = await walkFiles(source.directory)

  return files
    .filter(file => isSupportedAsset(file))
    .map(file => ({
      file,
      key: [
        source.keyPrefix,
        relative(source.directory, file).split(sep).join('/'),
      ].join('/'),
    }))
}

async function readJsonOrDefault(file, fallback) {
  try {
    return JSON.parse(await readFile(file, 'utf8'))
  } catch (error) {
    if (error.code === 'ENOENT') {
      return fallback
    }

    throw error
  }
}

async function walkFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = await Promise.all(entries.map(async (entry) => {
    const path = join(directory, entry.name)
    return entry.isDirectory() ? walkFiles(path) : [path]
  }))

  return files.flat()
}

function isSupportedAsset(file) {
  try {
    getContentType(file)
    return true
  } catch {
    return false
  }
}

function assertUniqueKeys(items) {
  const keys = new Set()

  for (const item of items) {
    if (keys.has(item.key)) {
      throw new Error(`Duplicate R2 object key: ${item.key}`)
    }

    keys.add(item.key)
  }
}

async function runConcurrent(items, concurrency, operation) {
  let nextIndex = 0

  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (nextIndex < items.length) {
      const index = nextIndex
      nextIndex += 1
      await operation(items[index], index)
    }
  }))
}

async function removeEmptyDirectories(roots) {
  for (const directory of roots) {
    const entries = await readdir(directory, { withFileTypes: true })

    for (const entry of entries) {
      if (entry.isDirectory()) {
        await removeEmptyDirectory(join(directory, entry.name))
      }
    }
  }
}

async function removeEmptyDirectory(directory) {
  const entries = await readdir(directory, { withFileTypes: true })

  for (const entry of entries) {
    if (entry.isDirectory()) {
      await removeEmptyDirectory(join(directory, entry.name))
    }
  }

  if ((await readdir(directory)).length === 0) {
    await rm(directory, { recursive: true })
  }
}
