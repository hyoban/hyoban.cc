import assert from 'node:assert/strict'
import { execFile as execFileCallback } from 'node:child_process'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { promisify } from 'node:util'

import { parseMomentDocument } from '../../src/moments/content.ts'

const execFile = promisify(execFileCallback)
const root = fileURLToPath(new URL('../../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')
const assetConfig = JSON.parse(
  await readFile(join(root, 'src/data/asset-config.json'), 'utf8'),
)

test('builds optimized public calendar pages without hidden moments', { timeout: 30_000 }, async () => {
  const mediaUrls = await collectMomentMediaUrls()
  const outputRoot = await mkdtemp(join(tmpdir(), 'hyoban-calendar-build-'))

  assert.ok(mediaUrls.hidden.length > 0, 'Expected at least one hidden moment fixture.')
  assert.ok(mediaUrls.visible.length > 0, 'Expected at least one visible moment fixture.')

  try {
    await execFile(
      'pnpm',
      ['exec', 'astro', 'build', '--outDir', outputRoot],
      { cwd: root, maxBuffer: 10 * 1024 * 1024 },
    )

    const calendarHtml = await readCalendarHtml(outputRoot)
    const leakedMedia = mediaUrls.hidden.filter(mediaUrl => calendarHtml.includes(mediaUrl))

    assert.deepEqual(leakedMedia, [])
    assert.ok(
      mediaUrls.visible.some(mediaUrl => calendarHtml.includes(mediaUrl)),
      'Expected visible Moment media in the calendar output.',
    )
    assert.doesNotMatch(
      calendarHtml,
      /href="\/calendar\/\d{4}\/\d{2}(?:\/\d{2})?"/,
      'Expected calendar links to use canonical trailing slashes.',
    )
    assert.match(
      calendarHtml,
      /data-calendar-month-prefetch="load"/,
      'Expected adjacent calendar months to be prefetched.',
    )
    assert.match(
      calendarHtml,
      /srcset="[^"]+-480w\.webp 480w/,
      'Expected calendar thumbnails to use responsive image variants.',
    )
  } finally {
    await rm(outputRoot, { force: true, recursive: true })
  }
})

async function collectMomentMediaUrls() {
  const files = (await readdir(contentRoot, { recursive: true }))
    .filter(file => file.endsWith('index.md'))
  const mediaUrls = { hidden: [], visible: [] }

  for (const file of files) {
    const document = await readFile(join(contentRoot, file), 'utf8')
    const id = file.replace(/\/index\.md$/, '')
    const moment = parseMomentDocument(document, { id })
    const media = moment.media[0]

    if (!media) {
      continue
    }

    const key = `moments/${id}/${media.file}`
      .split('/')
      .map(encodeURIComponent)
      .join('/')
    mediaUrls[moment.hidden ? 'hidden' : 'visible'].push(`${assetConfig.origin}/${key}`)
  }

  return mediaUrls
}

async function readCalendarHtml(outputRoot) {
  const calendarRoot = join(outputRoot, 'calendar')
  const files = (await readdir(calendarRoot, { recursive: true }))
    .filter(file => file.endsWith('.html'))
  const documents = await Promise.all(
    files.map(file => readFile(join(calendarRoot, file), 'utf8')),
  )

  return documents.join('\n')
}
