import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { parseMomentDocument } from '../src/moments/content.ts'

const root = fileURLToPath(new URL('../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')
const config = JSON.parse(
  await readFile(join(root, 'src/data/asset-config.json'), 'utf8'),
)
const remote = process.argv.includes('--remote')
const unsupportedArguments = process.argv.slice(2).filter(argument => argument !== '--remote')

if (unsupportedArguments.length > 0) {
  throw new Error('Usage: audit-moments.mjs [--remote]')
}

const files = (await readdir(contentRoot, { recursive: true }))
  .filter(file => file.endsWith('index.md'))
  .sort()
const objects = []
const ordersByDate = new Map()

for (const file of files) {
  const id = file.replace(/\/index\.md$/, '')
  const document = await readFile(join(contentRoot, file), 'utf8')
  const moment = parseMomentDocument(document, { id })
  const assets = JSON.parse(
    await readFile(join(contentRoot, id, 'assets.json'), 'utf8'),
  )
  const referenced = new Set()

  const idMatch = id.match(/^(\d{4})\/(\d{2})\/(\d{2})-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*$/)

  assert.ok(idMatch, `Invalid Moment id: ${id}`)
  const idDate = `${idMatch[1]}-${idMatch[2]}-${idMatch[3]}`
  const order = Number.parseInt(idMatch[4], 10)
  assert.equal(moment.occurredAt.slice(0, 10), idDate)
  ordersByDate.set(idDate, [...(ordersByDate.get(idDate) ?? []), order])

  for (const media of moment.media) {
    addAsset(media.file, media.type)

    if (media.poster) {
      addAsset(media.poster, 'image')
    }
  }

  assert.deepEqual(
    Object.keys(assets).sort(),
    [...referenced].sort(),
    `Unreferenced or missing asset metadata in ${id}`,
  )

  function addAsset(file, type) {
    const metadata = assets[file]

    assert.ok(metadata, `Missing metadata for moments/${id}/${file}`)
    assert.ok(Number.isInteger(metadata.bytes) && metadata.bytes > 0)
    assert.match(metadata.etag, /^[a-f0-9]{32}$/)
    assert.match(metadata.contentType, type === 'image' ? /^image\// : /^video\//)

    if (type === 'image') {
      assert.ok(Number.isInteger(metadata.width) && metadata.width > 0)
      assert.ok(Number.isInteger(metadata.height) && metadata.height > 0)
    }

    referenced.add(file)
    objects.push({
      key: `moments/${id}/${file}`,
      metadata,
    })
  }
}

for (const [date, orders] of ordersByDate) {
  orders.sort((first, second) => first - second)
  assert.deepEqual(
    orders,
    Array.from({ length: orders.length }, (_, index) => index + 1),
    `Moment orders are not contiguous for ${date}`,
  )
}

if (remote) {
  await auditRemoteObjects(objects)
}

console.log(
  `Moment audit passed: ${files.length} document(s), ${objects.length} R2 object(s)`
  + `${remote ? ', remote metadata verified' : ''}.`,
)

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
