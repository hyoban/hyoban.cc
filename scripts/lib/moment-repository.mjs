import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'

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
