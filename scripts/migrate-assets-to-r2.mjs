import { createHash } from 'node:crypto'
import { readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { basename, dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

import sharp from 'sharp'

import { getContentType, uploadR2Object } from './lib/r2-assets.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const configPath = join(root, 'src/data/asset-config.json')
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

for (const asset of assets) {
  if (!asset.key.startsWith('moments/')) {
    continue
  }

  const contents = await readFile(asset.file)
  const contentType = getContentType(asset.file)
  const metadata = {
    bytes: contents.byteLength,
    contentType,
    etag: createHash('md5').update(contents).digest('hex'),
  }

  if (contentType.startsWith('image/')) {
    const image = await sharp(asset.file).metadata()

    if (!image.width || !image.height) {
      throw new Error(`Unable to read image dimensions: ${asset.file}`)
    }

    metadata.height = image.height
    metadata.width = image.width
  }

  asset.metadata = metadata
}

if (!shouldUpload) {
  console.log(
    `Dry run found ${assets.length} local asset(s). Run with --upload to migrate them to R2.`,
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

await writeMomentAssetMetadata(assets)

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
    .map((file) => {
      const relativeFile = relative(source.directory, file).split(sep).join('/')

      return {
        file,
        key: [
        source.keyPrefix,
          relativeFile,
      ].join('/'),
        relativeFile,
      }
    })
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

async function writeMomentAssetMetadata(items) {
  const groups = Map.groupBy(
    items.filter(item => item.key.startsWith('moments/')),
    item => dirname(item.file),
  )

  for (const [directory, momentAssets] of groups) {
    const assetsPath = join(directory, 'assets.json')
    const assets = await readJsonOrDefault(assetsPath, {})

    for (const asset of momentAssets) {
      assets[basename(asset.file)] = asset.metadata
    }

    const sortedAssets = Object.fromEntries(
      Object.entries(assets).sort(([first], [second]) => first.localeCompare(second)),
    )
    await writeFile(assetsPath, `${JSON.stringify(sortedAssets, null, 2)}\n`)
  }
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
