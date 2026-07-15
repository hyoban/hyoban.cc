import { mkdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { basename, dirname, join, resolve } from 'node:path'

import {
  parseMomentDocument,
  serializeMomentDocument,
} from '../../src/moment-content.ts'

const TIME_ZONE = 'Asia/Singapore'

export async function createMoment(root, options = {}) {
  const now = options.now ?? new Date()
  const parts = getDateParts(now)
  const slug = sanitizeSlug(options.slug ?? 'note')
  const id = `${parts.year}/${parts.month}/${parts.day}-${parts.hour}${parts.minute}-${slug}`
  const directoryPath = resolve(root, id)
  const documentPath = join(directoryPath, 'index.md')

  await mkdir(dirname(directoryPath), { recursive: true })
  await mkdir(directoryPath, { recursive: false })
  await writeFile(documentPath, serializeMomentDocument({
    hidden: false,
    media: [],
    publishedAt: `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}+08:00`,
    text: 'Write the moment here.',
  }))

  return { directoryPath, documentPath, id }
}

export async function readMoment(root, id) {
  const normalizedId = normalizeMomentId(id)
  const documentPath = join(resolve(root), normalizedId, 'index.md')
  const document = await readFile(documentPath, 'utf8')

  return parseMomentDocument(document, { id: normalizedId })
}

export async function readMomentForPublication(root, id) {
  const moment = await readMoment(root, id)
  const directoryPath = join(resolve(root), moment.id)
  const media = await Promise.all(moment.media.map(async item => ({
    ...item,
    ...(item.poster
      ? { posterPath: await resolveMediaFile(directoryPath, item.poster) }
      : {}),
    sourcePath: await resolveMediaFile(directoryPath, item.file),
  })))

  return { ...moment, directoryPath, media }
}

export async function readPublicationReceipts(root, id) {
  const receiptPath = getReceiptPath(root, id)

  try {
    const document = JSON.parse(await readFile(receiptPath, 'utf8'))

    if (document.version !== 1 || !document.platforms || typeof document.platforms !== 'object') {
      throw new Error(`Invalid Publication Receipt document: ${receiptPath}`)
    }

    return document.platforms
  } catch (error) {
    if (error.code === 'ENOENT') {
      return {}
    }

    throw error
  }
}

export async function writePublicationReceipt(root, id, platform, receipt) {
  const receiptPath = getReceiptPath(root, id)
  const temporaryPath = `${receiptPath}.${process.pid}.tmp`
  const platforms = await readPublicationReceipts(root, id)
  const document = `${JSON.stringify({
    platforms: { ...platforms, [platform]: receipt },
    version: 1,
  }, null, 2)}\n`

  try {
    await writeFile(temporaryPath, document, { flag: 'wx' })
    await rename(temporaryPath, receiptPath)
  } catch (error) {
    await rm(temporaryPath, { force: true })
    throw error
  }
}

function getReceiptPath(root, id) {
  return join(resolve(root), normalizeMomentId(id), 'publication-receipts.json')
}

async function resolveMediaFile(directoryPath, file) {
  if (file !== basename(file)) {
    throw new Error(`Moment media must stay inside its directory: ${file}`)
  }

  const path = join(directoryPath, file)
  const metadata = await stat(path)

  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error(`Moment media is missing or empty: ${path}`)
  }

  return path
}

function normalizeMomentId(value) {
  if (typeof value !== 'string' || !/^\d{4}\/\d{2}\/[^/]+$/.test(value)) {
    throw new Error(`Invalid Moment id: ${value}`)
  }

  return value
}

function getDateParts(date) {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    day: '2-digit',
    hour: '2-digit',
    hourCycle: 'h23',
    minute: '2-digit',
    month: '2-digit',
    second: '2-digit',
    timeZone: TIME_ZONE,
    year: 'numeric',
  })

  return Object.fromEntries(
    formatter.formatToParts(date).map(part => [part.type, part.value]),
  )
}

function sanitizeSlug(value) {
  const sanitized = value
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')

  return sanitized || 'note'
}
