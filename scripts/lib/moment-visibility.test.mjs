import assert from 'node:assert/strict'
import { execFile as execFileCallback } from 'node:child_process'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { promisify } from 'node:util'

import { parseMomentDocument } from '../../src/moment-content.ts'

const execFile = promisify(execFileCallback)
const root = fileURLToPath(new URL('../../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')

test('excludes hidden moments from the public calendar build', { timeout: 30_000 }, async () => {
  const sourceUrls = await collectMomentSourceUrls()
  const outputRoot = await mkdtemp(join(tmpdir(), 'hyoban-calendar-build-'))

  assert.ok(sourceUrls.hidden.length > 0, 'Expected at least one hidden moment fixture.')
  assert.ok(sourceUrls.visible.length > 0, 'Expected at least one visible moment fixture.')

  try {
    await execFile(
      'pnpm',
      ['exec', 'astro', 'build', '--outDir', outputRoot],
      { cwd: root, maxBuffer: 10 * 1024 * 1024 },
    )

    const calendarHtml = await readCalendarHtml(outputRoot)
    const leakedSources = sourceUrls.hidden.filter(sourceUrl => calendarHtml.includes(sourceUrl))

    assert.deepEqual(leakedSources, [])
    assert.ok(
      sourceUrls.visible.some(sourceUrl => calendarHtml.includes(sourceUrl)),
      'Expected a visible moment source in the calendar output.',
    )
  } finally {
    await rm(outputRoot, { force: true, recursive: true })
  }
})

async function collectMomentSourceUrls() {
  const files = (await readdir(contentRoot, { recursive: true }))
    .filter(file => file.endsWith('index.md'))
  const sourceUrls = { hidden: [], visible: [] }

  for (const file of files) {
    const document = await readFile(join(contentRoot, file), 'utf8')
    const id = file.replace(/\/index\.md$/, '')
    const moment = parseMomentDocument(document, { id })

    if (!moment.provenance) {
      continue
    }

    sourceUrls[moment.hidden ? 'hidden' : 'visible'].push(moment.provenance.url)
  }

  return sourceUrls
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
