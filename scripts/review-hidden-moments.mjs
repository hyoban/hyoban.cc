import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { readMomentRecords } from './lib/moment-assets.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')
const output = resolve(
  process.argv[2]
  ?? join(root, '.artifacts', `hidden-moments-review-${new Date().toISOString().slice(0, 10)}.json`),
)
const records = await readMomentRecords(contentRoot)
const fingerprints = new Map()

for (const record of records) {
  const fingerprint = getFingerprint(record)
  fingerprints.set(fingerprint, [...(fingerprints.get(fingerprint) ?? []), record])
}

const items = records
  .filter(record => record.moment.hidden)
  .map((record) => {
    const matches = fingerprints.get(getFingerprint(record))
      .filter(match => match.id !== record.id)
    const visibleMatches = matches.filter(match => !match.moment.hidden)
    let classification = 'unique'

    if (visibleMatches.length > 0) {
      classification = 'duplicate-of-visible'
    } else if (matches.length > 0) {
      classification = 'duplicate-hidden'
    } else if (record.moment.text === '' && record.moment.media.length === 0) {
      classification = 'empty'
    }

    return {
      classification,
      id: record.id,
      matches: matches.map(match => match.id),
      mediaCount: record.moment.media.length,
      occurredAt: record.moment.occurredAt,
    }
  })
const counts = Object.fromEntries(
  ['duplicate-of-visible', 'duplicate-hidden', 'empty', 'unique']
    .map(classification => [
      classification,
      items.filter(item => item.classification === classification).length,
    ]),
)
const report = {
  generatedAt: new Date().toISOString(),
  hiddenCount: items.length,
  counts,
  items,
}

await mkdir(dirname(output), { recursive: true })
await writeFile(output, `${JSON.stringify(report, null, 2)}\n`)

console.log(`Hidden Moment review: ${items.length} hidden record(s).`)
console.log(JSON.stringify(counts, null, 2))
console.log(`Report: ${output}`)

function getFingerprint(record) {
  const value = {
    location: record.moment.location ?? null,
    media: record.moment.media.map(media => ({
      type: media.type,
      etag: record.assets[media.file]?.etag,
      posterEtag: media.poster ? record.assets[media.poster]?.etag : undefined,
    })),
    text: record.moment.text,
  }

  return createHash('sha256').update(JSON.stringify(value)).digest('hex')
}
