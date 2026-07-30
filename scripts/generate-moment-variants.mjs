import { createHash } from 'node:crypto'
import { execFile as execFileCallback } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { basename, extname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

import sharp from 'sharp'

import { readMomentRecords } from './lib/moment-assets.mjs'

const execFile = promisify(execFileCallback)
const root = fileURLToPath(new URL('../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')
const config = JSON.parse(await readFile(join(root, 'src/data/asset-config.json'), 'utf8'))
const temporaryPath = await mkdtemp(join(tmpdir(), 'hyoban-moment-variants-'))
const records = await readMomentRecords(contentRoot)
const tasks = records.flatMap(record =>
  record.moment.media
    .filter(media => media.type === 'image')
    .filter(media => record.assets[media.file].contentType !== 'image/gif')
    .filter(media => !record.assets[media.file].variants)
    .map(media => ({ media, record })),
)
const generated = []

try {
  let completed = 0

  await runConcurrent(tasks, 6, async ({ media, record }) => {
    const metadata = record.assets[media.file]
    const widths = [480, 960, 1920].filter(width => width < metadata.width)

    if (widths.length === 0) {
      metadata.variants = []
      completed += 1
      return
    }

    const sourceUrl = `${config.origin}/moments/${record.id}/${encodeURIComponent(media.file)}`
    const response = await fetchWithRetries(sourceUrl)

    if (!response.ok) {
      throw new Error(`Unable to download ${sourceUrl}: ${response.status}`)
    }

    const source = Buffer.from(await response.arrayBuffer())
    assertSource(source, metadata, sourceUrl)
    const stem = basename(media.file, extname(media.file))
    const directoryToken = createHash('sha1').update(record.id).digest('hex').slice(0, 12)
    const variants = []

    for (const width of widths) {
      const file = `${stem}-${width}w.webp`
      const path = join(temporaryPath, `${directoryToken}-${file}`)
      const info = await sharp(source)
        .rotate()
        .resize({ width, withoutEnlargement: true })
        .webp({ effort: 6, quality: 80 })
        .toFile(path)
      const contents = await readFile(path)
      const variant = {
        file,
        key: `moments/${record.id}/${file}`,
        metadata: {
          bytes: contents.byteLength,
          contentType: 'image/webp',
          etag: createHash('md5').update(contents).digest('hex'),
          height: info.height,
          width: info.width,
        },
        path,
        record,
      }
      variants.push(variant)
      generated.push(variant)
    }

    metadata.variants = variants.map(variant => variant.file)
    completed += 1

    if (completed % 50 === 0 || completed === tasks.length) {
      console.log(`Prepared variants for ${completed}/${tasks.length} image(s).`)
    }
  })

  const missing = []
  let reused = 0

  await runConcurrent(generated, 12, async (variant) => {
    const remote = await inspectRemote(variant)

    if (remote === 'missing') {
      missing.push(variant)
    } else {
      reused += 1
    }
  })

  await uploadMissingWithRetries(missing)

  let verified = 0

  await runConcurrent(generated, 12, async (variant) => {
    const remote = await inspectRemote(variant)

    if (remote !== 'verified') {
      throw new Error(`R2 verification failed after upload: ${variant.key}`)
    }

    verified += 1

    if (verified % 100 === 0 || verified === generated.length) {
      console.log(`Verified ${verified}/${generated.length} responsive variant(s).`)
    }
  })

  for (const record of records) {
    const recordVariants = generated.filter(variant => variant.record === record)

    for (const variant of recordVariants) {
      record.assets[variant.file] = variant.metadata
    }

    if (
      recordVariants.length > 0
      || record.moment.media.some(media =>
        media.type === 'image' && record.assets[media.file].variants?.length === 0,
      )
    ) {
      const sorted = Object.fromEntries(
        Object.entries(record.assets).sort(([first], [second]) => first.localeCompare(second)),
      )
      await writeFile(
        join(record.directory, 'assets.json'),
        `${JSON.stringify(sorted, null, 2)}\n`,
      )
    }
  }

  console.log(
    `Moment variants complete: ${generated.length} verified `
    + `(${missing.length} uploaded, ${reused} reused) across ${tasks.length} image(s).`,
  )
} finally {
  await rm(temporaryPath, { force: true, recursive: true })
}

async function uploadMissingWithRetries(initialMissing) {
  let pending = initialMissing
  const wrangler = join(root, 'node_modules', '.bin', 'wrangler')

  for (let attempt = 1; attempt <= 5 && pending.length > 0; attempt += 1) {
    const manifestPath = join(temporaryPath, `bulk-upload-${attempt}.json`)
    await writeFile(
      manifestPath,
      `${JSON.stringify(pending.map(variant => ({
        file: variant.path,
        key: variant.key,
      })), null, 2)}\n`,
    )

    try {
      await execFile(wrangler, [
        'r2',
        'bulk',
        'put',
        config.bucket,
        '--filename',
        manifestPath,
        '--content-type',
        'image/webp',
        '--cache-control',
        'public, max-age=31536000, immutable',
        '--concurrency',
        '10',
        '--remote',
        '--force',
      ], {
        cwd: root,
        maxBuffer: 20 * 1024 * 1024,
      })
    } catch (error) {
      console.warn(`Bulk upload attempt ${attempt} was interrupted; checking remaining objects.`)
    }

    const stillMissing = []

    await runConcurrent(pending, 12, async (variant) => {
      if (await inspectRemote(variant) === 'missing') {
        stillMissing.push(variant)
      }
    })
    pending = stillMissing

    if (pending.length > 0) {
      console.log(`${pending.length} variant(s) still missing after upload attempt ${attempt}.`)
    }
  }

  if (pending.length > 0) {
    throw new Error(`Unable to upload ${pending.length} responsive variant(s) after 5 attempts.`)
  }
}

function assertSource(contents, metadata, url) {
  if (contents.byteLength !== metadata.bytes) {
    throw new Error(`Source size mismatch: ${url}`)
  }

  const etag = createHash('md5').update(contents).digest('hex')

  if (etag !== metadata.etag) {
    throw new Error(`Source ETag mismatch: ${url}`)
  }
}

async function inspectRemote(variant) {
  const url = `${config.origin}/${variant.key.split('/').map(encodeURIComponent).join('/')}`
  const response = await fetchWithRetries(url, { method: 'HEAD' })

  if (response.status === 404) {
    return 'missing'
  }

  if (
    response.status !== 200
    || Number.parseInt(response.headers.get('content-length') ?? '', 10) !== variant.metadata.bytes
    || response.headers.get('content-type') !== variant.metadata.contentType
    || response.headers.get('etag')?.replaceAll('"', '') !== variant.metadata.etag
  ) {
    throw new Error(`Refusing to overwrite a mismatched R2 object: ${variant.key}`)
  }

  return 'verified'
}

async function fetchWithRetries(url, options) {
  let lastError

  for (let attempt = 1; attempt <= 5; attempt += 1) {
    try {
      const response = await fetch(url, {
        cache: 'no-store',
        ...options,
      })

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

  throw new Error(`Failed to fetch ${url} after 5 attempts.`, { cause: lastError })
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
