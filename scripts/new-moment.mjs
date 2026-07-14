import { mkdir, writeFile } from 'node:fs/promises'

const TIME_ZONE = 'Asia/Singapore'
const rootUrl = new URL('../', import.meta.url)
const slug = sanitizeSlug(process.argv[2] ?? 'note')
const now = new Date()
const parts = getDateParts(now)
const publishedAt = `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}+08:00`
const baseName = `${parts.day}-${parts.hour}${parts.minute}-${slug}`
const directoryUrl = new URL(
  `src/content/moments/${parts.year}/${parts.month}/${baseName}/`,
  rootUrl,
)

await mkdir(new URL('../', directoryUrl), { recursive: true })
await mkdir(directoryUrl, { recursive: false })
await writeFile(
  new URL('index.md', directoryUrl),
  [
    '---',
    `publishedAt: ${JSON.stringify(publishedAt)}`,
    '# occurredOn: "YYYY-MM-DD"',
    '# hidden: true',
    '# Add media files beside this document and describe them here.',
    '# media:',
    '#   - type: image',
    '#     file: "image-1.jpg"',
    '#     alt: "Describe the image"',
    'media: []',
    '---',
    '',
    'Write the moment here.',
    '',
  ].join('\n'),
)

console.log(directoryUrl.pathname)

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
