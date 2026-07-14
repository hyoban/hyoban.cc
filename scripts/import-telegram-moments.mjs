import { cp, mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { basename, isAbsolute, relative, resolve } from 'node:path'

import { parseTelegramExport } from './lib/telegram-export.mjs'
import { generateVideoPoster, serializeMoment } from './lib/moment-files.mjs'

const rootUrl = new URL('../', import.meta.url)
const outputUrl = new URL('src/content/moments/', rootUrl)
const telegramSource = {
  channel: 'hyoban_travel',
  chatId: 3981320482,
}
const { exportPath, refresh } = parseArguments(process.argv.slice(2))
const { channel } = telegramSource
const exportRoot = resolve(exportPath)
const exportData = JSON.parse(await readFile(resolve(exportRoot, 'result.json'), 'utf8'))
const parsed = parseTelegramExport(exportData, telegramSource)
const existingEntries = await findExistingEntries(channel)
const targets = parsed.moments.filter(moment => refresh || !existingEntries.has(moment.id))

let importedCount = 0
let refreshedCount = 0
let mediaCount = 0
let posterCount = 0

for (const moment of targets) {
  const existingDirectory = existingEntries.get(moment.id)
  const result = await importMoment(moment, {
    directoryUrl: existingDirectory,
    replace: refresh && Boolean(existingDirectory),
  })

  if (result.status === 'skipped') {
    continue
  }

  if (result.status === 'refreshed') {
    refreshedCount += 1
  } else {
    importedCount += 1
  }

  mediaCount += result.mediaCount
  posterCount += result.posterCount
  console.log(
    `${result.status === 'refreshed' ? 'Refreshed' : 'Imported'} ${channel}/${moment.id} (${result.mediaCount} media).`,
  )
}

let removedIgnoredCount = 0

if (refresh) {
  for (const id of parsed.ignoredIds) {
    const directoryUrl = existingEntries.get(id)

    if (directoryUrl) {
      await rm(directoryUrl, { force: true, recursive: true })
      removedIgnoredCount += 1
    }
  }
}

console.log(
  `Imported ${importedCount} new and refreshed ${refreshedCount} moment(s) with ${mediaCount} media file(s) and ${posterCount} video poster(s); removed ${removedIgnoredCount} ignored existing moment(s).`,
)
console.log(
  `Ignored ${parsed.skippedForwarded} forwarded, ${parsed.skippedService} service, and ${parsed.skippedOtherAuthors} other-author message(s) from ${parsed.chatName}.`,
)

async function importMoment(moment, options) {
  const [year, month] = moment.publishedAt.slice(0, 10).split('-')
  const defaultDirectoryUrl = new URL(
    `${year}/${month}/telegram-${moment.id}/`,
    outputUrl,
  )
  const directoryUrl = options.directoryUrl ?? defaultDirectoryUrl
  const parentUrl = new URL('../', directoryUrl)
  const directoryName = basename(directoryUrl.pathname)
  const temporaryUrl = new URL(`.${directoryName}.tmp/`, parentUrl)
  const backupUrl = new URL(`.${directoryName}.backup/`, parentUrl)

  await mkdir(parentUrl, { recursive: true })

  if (await exists(directoryUrl) && !options.replace) {
    return { mediaCount: 0, posterCount: 0, status: 'skipped' }
  }

  await rm(temporaryUrl, { force: true, recursive: true })
  await rm(backupUrl, { force: true, recursive: true })
  await mkdir(temporaryUrl)

  try {
    const media = []

    for (const [index, item] of moment.media.entries()) {
      const file = `${item.type}-${index + 1}${item.extension}`
      const destinationUrl = new URL(file, temporaryUrl)
      const sourceFile = await resolveExportFile(item.sourcePath)

      await cp(sourceFile, destinationUrl)

      const mediaItem = { alt: '', file, type: item.type }

      if (item.type === 'video') {
        const poster = `video-${index + 1}-poster.jpg`
        const posterUrl = new URL(poster, temporaryUrl)

        if (item.posterPath) {
          await cp(await resolveExportFile(item.posterPath), posterUrl)
          mediaItem.poster = poster
        } else {
          const generatedPoster = `video-${index + 1}-poster.png`

          if (generateVideoPoster(destinationUrl, new URL(generatedPoster, temporaryUrl))) {
            mediaItem.poster = generatedPoster
          }
        }
      }

      media.push(mediaItem)
    }

    await writeFile(
      new URL('index.md', temporaryUrl),
      serializeMoment({
        media,
        publishedAt: moment.publishedAt,
        sourceUrl: moment.sourceUrl,
        text: moment.text,
      }),
    )

    const replacing = await exists(directoryUrl)

    if (replacing) {
      await rename(directoryUrl, backupUrl)
    }

    try {
      await rename(temporaryUrl, directoryUrl)
    } catch (error) {
      if (replacing) {
        await rename(backupUrl, directoryUrl)
      }

      throw error
    }

    await rm(backupUrl, { force: true, recursive: true })

    return {
      mediaCount: media.length,
      posterCount: media.filter(item => item.poster).length,
      status: replacing ? 'refreshed' : 'imported',
    }
  } catch (error) {
    await rm(temporaryUrl, { force: true, recursive: true })
    throw error
  }
}

async function resolveExportFile(path) {
  const absolutePath = resolve(exportRoot, path)
  const relativePath = relative(exportRoot, absolutePath)

  if (!relativePath || relativePath.startsWith('..') || isAbsolute(relativePath)) {
    throw new Error(`Export media path escapes the export directory: ${path}`)
  }

  const file = await stat(absolutePath)

  if (!file.isFile() || file.size === 0) {
    throw new Error(`Export media file is missing or empty: ${path}`)
  }

  return absolutePath
}

async function findExistingEntries(channelName) {
  const entries = new Map()
  const files = await readdir(outputUrl, { recursive: true }).catch(() => [])
  const pattern = new RegExp(
    `sourceUrl:\\s*["']https://(?:t\\.me|telegram\\.me)/${escapeRegExp(channelName)}/(\\d+)`,
  )

  for (const file of files) {
    if (!file.endsWith('index.md')) {
      continue
    }

    const indexUrl = new URL(file, outputUrl)
    const document = await readFile(indexUrl, 'utf8')
    const id = document.match(pattern)?.[1]

    if (id) {
      entries.set(id, new URL('./', indexUrl))
    }
  }

  return entries
}

function parseArguments(args) {
  const refresh = args.includes('--refresh')
  const unknownOptions = args.filter(argument => argument.startsWith('--') && argument !== '--refresh')
  const positionalArgs = args.filter(argument => !argument.startsWith('--'))
  const [exportPath] = positionalArgs

  if (!exportPath || positionalArgs.length > 1 || unknownOptions.length > 0) {
    throw new Error(
      'Usage: pnpm moment:import-telegram <export-directory> [--refresh]',
    )
  }

  return {
    exportPath,
    refresh,
  }
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

async function exists(url) {
  try {
    await stat(url)
    return true
  } catch {
    return false
  }
}
