import assert from 'node:assert/strict'
import { execFile as execFileCallback } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { basename, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

import {
  getReferencedAssetFiles,
  getReferencedR2Objects,
  momentIdPattern,
  readMomentRecords,
} from './lib/moment-assets.mjs'

const execFile = promisify(execFileCallback)
const root = fileURLToPath(new URL('../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')
const config = JSON.parse(
  await readFile(join(root, 'src/data/asset-config.json'), 'utf8'),
)
const options = parseArguments(process.argv.slice(2))
const records = await readMomentRecords(contentRoot)
const objects = getReferencedR2Objects(records)
const ordersByDate = new Map()
const blankAltIds = []
const changedMomentIds = options.changedFrom
  ? await getChangedMomentIds(options.changedFrom)
  : new Set()

for (const record of records) {
  const idMatch = record.id.match(momentIdPattern)

  assert.ok(idMatch, `Invalid Moment id: ${record.id}`)
  const idDate = `${idMatch[1]}-${idMatch[2]}-${idMatch[3]}`
  const order = Number.parseInt(idMatch[4], 10)
  assert.equal(record.moment.occurredAt.slice(0, 10), idDate)
  ordersByDate.set(idDate, [...(ordersByDate.get(idDate) ?? []), order])

  const directFiles = new Set()

  for (const media of record.moment.media) {
    assert.ok(!directFiles.has(media.file), `Duplicate media reference in ${record.id}: ${media.file}`)
    directFiles.add(media.file)
    validateAsset(record, media.file, media.type)

    if (!record.moment.hidden && media.alt.trim() === '') {
      blankAltIds.push(`${record.id}/${media.file}`)

      assert.ok(
        !changedMomentIds.has(record.id),
        `Visible changed Moment requires non-empty alt text: ${record.id}/${media.file}`,
      )
    }

    if (media.type === 'video' && media.poster) {
      assert.notEqual(media.poster, media.file, `Video poster matches video in ${record.id}`)
      validateAsset(record, media.poster, 'image')
    }
  }

  assert.deepEqual(
    Object.keys(record.assets).sort(),
    [...getReferencedAssetFiles(record)].sort(),
    `Unreferenced or missing asset metadata in ${record.id}`,
  )
}

for (const [date, orders] of ordersByDate) {
  orders.sort((first, second) => first - second)
  assert.deepEqual(
    orders,
    Array.from({ length: orders.length }, (_, index) => index + 1),
    `Moment orders are not contiguous for ${date}`,
  )
}

if (options.remote) {
  await auditRemoteObjects(objects)
}

console.log(
  `Moment audit passed: ${records.length} document(s), ${objects.length} R2 object(s)`
  + `${options.remote ? ', remote metadata verified' : ''}.`,
)

if (blankAltIds.length > 0) {
  console.warn(
    `Accessibility warning: ${blankAltIds.length} visible media item(s) have empty alt text.`
    + ' Existing entries remain valid; changed visible Moments must fix them.',
  )
}

function validateAsset(record, file, type) {
  assert.equal(basename(file), file, `Asset filename must not contain a path: ${record.id}/${file}`)
  assert.match(
    file,
    /^[a-z0-9]+(?:-[a-z0-9]+)*\.[a-z0-9]+$/,
    `Unsafe asset filename: ${record.id}/${file}`,
  )
  const metadata = record.assets[file]

  assert.ok(metadata, `Missing metadata for moments/${record.id}/${file}`)
  assert.ok(Number.isInteger(metadata.bytes) && metadata.bytes > 0)
  assert.match(metadata.etag, /^[a-f0-9]{32}$/)
  assert.match(metadata.contentType, type === 'image' ? /^image\// : /^video\//)

  if (type === 'image') {
    assert.ok(Number.isInteger(metadata.width) && metadata.width > 0)
    assert.ok(Number.isInteger(metadata.height) && metadata.height > 0)

    const widths = []

    for (const variant of metadata.variants ?? []) {
      assert.notEqual(variant, file, `Image lists itself as a variant: ${record.id}/${file}`)
      const variantMetadata = record.assets[variant]

      assert.ok(variantMetadata, `Missing variant metadata: ${record.id}/${variant}`)
      assert.equal(variantMetadata.variants, undefined, `Nested variants are not allowed: ${record.id}/${variant}`)
      assert.match(variantMetadata.contentType, /^image\//)
      assert.ok(Number.isInteger(variantMetadata.width) && variantMetadata.width > 0)
      assert.ok(Number.isInteger(variantMetadata.height) && variantMetadata.height > 0)
      assert.ok(variantMetadata.width < metadata.width, `Variant is not narrower than source: ${record.id}/${variant}`)
      assert.ok(
        Math.abs(
          variantMetadata.height
          - Math.round(variantMetadata.width * metadata.height / metadata.width),
        ) <= 1,
        `Variant aspect ratio mismatch: ${record.id}/${variant}`,
      )
      widths.push(variantMetadata.width)
    }

    assert.equal(new Set(metadata.variants ?? []).size, (metadata.variants ?? []).length)
    assert.deepEqual(widths, [...widths].sort((first, second) => first - second))
  } else {
    assert.equal(metadata.width, undefined, `Video metadata must not have width: ${record.id}/${file}`)
    assert.equal(metadata.height, undefined, `Video metadata must not have height: ${record.id}/${file}`)
    assert.equal(metadata.variants, undefined, `Video metadata must not have variants: ${record.id}/${file}`)
  }
}

async function auditRemoteObjects(items) {
  let nextIndex = 0
  let completed = 0

  await Promise.all(Array.from({ length: 8 }, async () => {
    while (nextIndex < items.length) {
      const index = nextIndex
      nextIndex += 1
      const item = items[index]
      const url = `${config.origin}/${item.key.split('/').map(encodeURIComponent).join('/')}`
      const response = await headWithRetries(url)

      assert.equal(response.status, 200, `Missing R2 object: ${item.key}`)
      assert.equal(
        Number.parseInt(response.headers.get('content-length') ?? '', 10),
        item.metadata.bytes,
        `R2 size mismatch: ${item.key}`,
      )
      assert.equal(
        response.headers.get('content-type'),
        item.metadata.contentType,
        `R2 content type mismatch: ${item.key}`,
      )
      assert.equal(
        response.headers.get('etag')?.replaceAll('"', ''),
        item.metadata.etag,
        `R2 ETag mismatch: ${item.key}`,
      )

      completed += 1

      if (completed % 100 === 0 || completed === items.length) {
        console.log(`Audited ${completed}/${items.length} remote object(s).`)
      }
    }
  }))
}

async function getChangedMomentIds(ref) {
  const { stdout } = await execFile('git', [
    'diff',
    '--name-only',
    '--diff-filter=AM',
    `${ref}...HEAD`,
    '--',
    'src/content/moments/**/index.md',
  ], { cwd: root })

  return new Set(
    stdout.trim().split('\n')
      .filter(Boolean)
      .map(file => file.replace(/^src\/content\/moments\//, '').replace(/\/index\.md$/, '')),
  )
}

async function headWithRetries(url) {
  let lastError

  for (let attempt = 1; attempt <= 5; attempt += 1) {
    try {
      const response = await fetch(url, {
        cache: 'no-store',
        method: 'HEAD',
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

  throw new Error(`Failed to inspect ${url} after 5 attempts.`, { cause: lastError })
}

function parseArguments(argv) {
  const options = {
    changedFrom: undefined,
    remote: false,
  }

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]

    if (argument === '--remote') {
      options.remote = true
    } else if (argument === '--changed-from' && argv[index + 1]) {
      options.changedFrom = argv[index + 1]
      index += 1
    } else {
      throw new Error('Usage: audit-moments.mjs [--remote] [--changed-from <git-ref>]')
    }
  }

  return options
}
