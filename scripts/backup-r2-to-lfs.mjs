import { createHash } from 'node:crypto'
import {
  mkdir,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

import { listR2Objects } from './lib/cloudflare-r2-api.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const options = parseArguments(process.argv.slice(2))
const toolRoot = resolve(options.toolRoot ?? root)
const backupRoot = join(root, 'r2')
const partsRoot = join(backupRoot, '.parts')
const config = JSON.parse(
  await readFile(join(root, 'src/data/asset-config.json'), 'utf8'),
)
const manifestPath = join(root, 'r2-manifest.json')
const previousManifest = await readJsonIfExists(manifestPath)
const previousObjects = new Map(
  (previousManifest?.objects ?? []).map(object => [object.key, object]),
)
const inventory = await getInventory()
const inventoryDigest = digestInventory(inventory)
const inventoryKeys = new Set(inventory.map(object => object.key))
const contentTypes = new Map()
let completed = 0
let downloaded = 0
let removed = 0
let reused = 0

await mkdir(partsRoot, { recursive: true })

try {
  await runConcurrent(inventory, 12, async (object) => {
    const file = resolveBackupPath(object.key)
    const previous = previousObjects.get(object.key)

    if (
      matchesManifestObject(previous, object)
      && await isFile(file)
    ) {
      reused += 1
      contentTypes.set(object.key, previous.contentType)
    } else if (await matchesObject(file, object)) {
      reused += 1
      contentTypes.set(object.key, previous?.contentType ?? null)
    } else {
      contentTypes.set(object.key, await downloadObject(object, file))
      downloaded += 1
    }

    completed += 1

    if (completed % 100 === 0 || completed === inventory.length) {
      console.log(`Verified ${completed}/${inventory.length} R2 object(s).`)
    }
  })

  for (const file of await listBackupFiles()) {
    const key = relative(backupRoot, file).split(sep).join('/')

    if (!inventoryKeys.has(key)) {
      await rm(file)
      removed += 1
    }
  }

  const finalInventory = await getInventory()
  const finalDigest = digestInventory(finalInventory)

  if (finalDigest !== inventoryDigest) {
    throw new Error(
      'R2 inventory changed while the snapshot was downloading. Rerun to create a consistent manifest.',
    )
  }

  const manifestIsCurrent = (
    previousManifest?.inventorySha256 === inventoryDigest
    && downloaded === 0
    && removed === 0
  )

  if (manifestIsCurrent) {
    console.log(
      `R2 snapshot is already current: ${inventory.length} object(s), `
      + `${formatBytes(previousManifest.totalBytes)} (${reused} reused).`,
    )
    console.log(`Inventory SHA-256: ${inventoryDigest}`)
  }
  else {
    const manifest = {
      version: 1,
      accountId: config.accountId,
      bucket: config.bucket,
      origin: config.origin,
      createdAt: new Date().toISOString(),
      inventorySha256: inventoryDigest,
      objectCount: inventory.length,
      totalBytes: inventory.reduce((sum, object) => sum + object.size, 0),
      objects: inventory.map(object => ({
        key: object.key,
        bytes: object.size,
        contentType: contentTypes.get(object.key)
          ?? object.http_metadata?.contentType
          ?? previousObjects.get(object.key)?.contentType
          ?? null,
        etag: object.etag,
        lastModified: object.last_modified,
        storageClass: previousObjects.get(object.key)?.storageClass
          ?? normalizeStorageClass(object.storage_class)
          ?? null,
      })),
    }

    await writeFile(
      manifestPath,
      `${JSON.stringify(manifest, null, 2)}\n`,
    )

    console.log(
      `R2 snapshot complete: ${inventory.length} object(s), `
      + `${formatBytes(manifest.totalBytes)} `
      + `(${downloaded} downloaded, ${reused} reused, ${removed} removed).`,
    )
    console.log(`Inventory SHA-256: ${inventoryDigest}`)
  }
} finally {
  await rm(partsRoot, { force: true, recursive: true })
}

async function getInventory() {
  return (await listR2Objects({
    accountId: config.accountId,
    bucket: config.bucket,
    prefix: '',
    root: toolRoot,
  })).sort((first, second) => first.key.localeCompare(second.key))
}

async function matchesObject(file, object) {
  try {
    const fileStat = await stat(file)

    if (!fileStat.isFile() || fileStat.size !== object.size) {
      return false
    }

    if (!isMd5Etag(object.etag)) {
      return true
    }

    const contents = await readFile(file)
    return createHash('md5').update(contents).digest('hex') === object.etag
  } catch (error) {
    if (error.code === 'ENOENT') {
      return false
    }

    throw error
  }
}

async function downloadObject(object, file) {
  const url = `${config.origin}/${object.key.split('/').map(encodeURIComponent).join('/')}`
  const response = await fetchWithRetries(url)

  if (!response.ok) {
    throw new Error(`Unable to download ${object.key}: ${response.status} ${response.statusText}`)
  }

  const headerSize = Number.parseInt(response.headers.get('content-length') ?? '', 10)
  const headerEtag = response.headers.get('etag')?.replaceAll('"', '')

  if (headerSize !== object.size || headerEtag !== object.etag) {
    throw new Error(`R2 response metadata mismatch: ${object.key}`)
  }

  const contents = Buffer.from(await response.arrayBuffer())
  const contentEtag = isMd5Etag(object.etag)
    ? createHash('md5').update(contents).digest('hex')
    : object.etag

  if (contents.byteLength !== object.size || contentEtag !== object.etag) {
    throw new Error(`R2 object content mismatch: ${object.key}`)
  }

  await mkdir(dirname(file), { recursive: true })
  const part = join(partsRoot, createHash('sha256').update(object.key).digest('hex'))
  await writeFile(part, contents)
  await rename(part, file)

  return response.headers.get('content-type')?.split(';', 1)[0] ?? null
}

function resolveBackupPath(key) {
  const file = resolve(backupRoot, key)

  if (!file.startsWith(`${backupRoot}${sep}`)) {
    throw new Error(`Unsafe R2 object key: ${key}`)
  }

  return file
}

function digestInventory(items) {
  return createHash('sha256')
    .update(JSON.stringify(items.map(object => ({
      etag: object.etag,
      key: object.key,
      lastModified: object.last_modified,
      size: object.size,
    }))))
    .digest('hex')
}

function matchesManifestObject(previous, current) {
  return previous
    && previous.bytes === current.size
    && previous.etag === current.etag
    && previous.lastModified === current.last_modified
}

function isMd5Etag(etag) {
  return /^[a-f0-9]{32}$/i.test(etag)
}

function normalizeStorageClass(storageClass) {
  return storageClass === 'STANDARD' ? 'Standard' : storageClass
}

async function isFile(file) {
  try {
    return (await stat(file)).isFile()
  } catch (error) {
    if (error.code === 'ENOENT') {
      return false
    }

    throw error
  }
}

async function listBackupFiles(directory = backupRoot) {
  const files = []

  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = join(directory, entry.name)

    if (file === partsRoot) {
      continue
    }

    if (entry.isDirectory()) {
      files.push(...await listBackupFiles(file))
    } else if (entry.isFile()) {
      files.push(file)
    }
  }

  return files
}

async function readJsonIfExists(file) {
  try {
    return JSON.parse(await readFile(file, 'utf8'))
  } catch (error) {
    if (error.code === 'ENOENT') {
      return null
    }

    throw error
  }
}

async function fetchWithRetries(url) {
  let lastError

  for (let attempt = 1; attempt <= 5; attempt += 1) {
    try {
      const response = await fetch(url, { cache: 'no-store' })

      if (response.status === 429 || response.status >= 500) {
        throw new Error(`${response.status} ${response.statusText}`)
      }

      return response
    } catch (error) {
      lastError = error

      if (attempt < 5) {
        await new Promise(resolve => setTimeout(resolve, 250 * 2 ** (attempt - 1)))
      }
    }
  }

  throw new Error(`Failed to download ${url} after 5 attempts.`, { cause: lastError })
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

function formatBytes(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(2)} MiB`
}

function parseArguments(argv) {
  if (argv.length === 0) {
    return {}
  }

  if (argv.length === 2 && argv[0] === '--tool-root') {
    return { toolRoot: argv[1] }
  }

  throw new Error('Usage: backup-r2-to-lfs.mjs [--tool-root <project-path>]')
}
