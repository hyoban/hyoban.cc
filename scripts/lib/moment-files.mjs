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

export function serializeMoment(moment) {
  const lines = [
    '---',
    `publishedAt: ${JSON.stringify(moment.publishedAt)}`,
    `sourceUrl: ${JSON.stringify(moment.sourceUrl)}`,
    'media:',
  ]

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
