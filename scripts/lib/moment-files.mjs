import { execFileSync } from 'node:child_process'
import { basename } from 'node:path'

export function generateVideoPoster(videoUrl, posterUrl) {
  try {
    const outputDirectory = new URL('./', posterUrl)

    execFileSync('/usr/bin/qlmanage', [
      '-t',
      '-s',
      '1200',
      '-o',
      outputDirectory.pathname,
      videoUrl.pathname,
    ], { stdio: 'ignore' })

    const generatedUrl = new URL(`${basename(videoUrl.pathname)}.png`, outputDirectory)
    execFileSync('/bin/mv', [generatedUrl.pathname, posterUrl.pathname])
    return true
  } catch {
    return false
  }
}

export function parseMomentOccurredOn(document) {
  const frontmatter = parseFrontmatter(document)
  return frontmatter?.match(/^occurredOn:\s*["']?(\d{4}-\d{2}-\d{2})["']?\s*$/m)?.[1]
}

export function parseMomentHidden(document) {
  const frontmatter = parseFrontmatter(document)
  const value = frontmatter?.match(
    /^hidden:\s*(true|false)(?:\s+#.*)?\s*$/im,
  )?.[1]

  if (value === undefined) {
    return undefined
  }

  return value.toLowerCase() === 'true'
}

export function parseMomentSourceUrl(document) {
  const frontmatter = parseFrontmatter(document)
  const value = frontmatter?.match(/^sourceUrl:\s*(?:"([^"]+)"|'([^']+)'|(\S+))\s*$/m)
  return value?.[1] ?? value?.[2] ?? value?.[3]
}

export function serializeImportedMoment(moment, existingDocument) {
  const existingMetadata = existingDocument
    ? {
        hidden: parseMomentHidden(existingDocument),
        occurredOn: parseMomentOccurredOn(existingDocument),
      }
    : {}

  return serializeMoment({ ...moment, ...existingMetadata })
}

export function serializeMoment(moment) {
  const lines = [
    '---',
    `publishedAt: ${JSON.stringify(moment.publishedAt)}`,
  ]

  if (moment.occurredOn) {
    lines.push(`occurredOn: ${JSON.stringify(moment.occurredOn)}`)
  }

  if (moment.hidden) {
    lines.push('hidden: true')
  }

  lines.push(
    `sourceUrl: ${JSON.stringify(moment.sourceUrl)}`,
    'media:',
  )

  if (moment.media.length === 0) {
    lines[lines.length - 1] = 'media: []'
  } else {
    for (const item of moment.media) {
      lines.push(`  - type: ${item.type}`)
      lines.push(`    file: ${JSON.stringify(item.file)}`)
      lines.push(`    alt: ${JSON.stringify(item.alt)}`)

      if (item.poster) {
        lines.push(`    poster: ${JSON.stringify(item.poster)}`)
      }
    }
  }

  lines.push('---')

  if (moment.text) {
    lines.push('', moment.text)
  }

  lines.push('')
  return lines.join('\n')
}

function parseFrontmatter(document) {
  return document.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/)?.[1]
}
