import { cp, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import { basename, extname, join } from 'node:path'

import { generateVideoPoster, serializeMoment } from './lib/moment-files.mjs'

const rootUrl = new URL('../', import.meta.url)
const outputUrl = new URL('src/content/moments/', rootUrl)
const args = process.argv.slice(2)
const force = args.includes('--force')
const positionalArgs = args.filter(arg => arg !== '--force')
const [archivePath, mediaPath] = positionalArgs

if (!archivePath || !mediaPath) {
  throw new Error(
    'Usage: pnpm moment:import-twitter <archive.json> <media-directory> [--force]',
  )
}

const archive = JSON.parse(await readFile(archivePath, 'utf8'))

if (!Array.isArray(archive)) {
  throw new TypeError('The Twitter archive must contain an array of posts.')
}

const existingEntries = await readdir(outputUrl, { recursive: true }).catch(() => [])

if (existingEntries.length > 0 && !force) {
  throw new Error('src/content/moments is not empty. Pass --force to replace it.')
}

if (force) {
  await rm(outputUrl, { force: true, recursive: true })
}

await mkdir(outputUrl, { recursive: true })

const mediaFiles = await readdir(mediaPath)
const authoredPosts = archive
  .filter(isAuthoredPost)
  .sort((first, second) => first.created_at.localeCompare(second.created_at))

let copiedMediaCount = 0
let generatedPosterCount = 0

for (const post of authoredPosts) {
  const publishedAt = normalizeDate(post.created_at)
  const [year, month, day] = publishedAt.slice(0, 10).split('-')
  const directoryName = `${day}-${post.id}`
  const directoryUrl = new URL(`${year}/${month}/${directoryName}/`, outputUrl)
  const sourceMedia = mediaFiles
    .filter(file => file.startsWith(`${post.screen_name}_${post.id}_`))
    .sort(compareExportedMedia)
  const extendedMedia = post.metadata?.legacy?.extended_entities?.media ?? []
  const media = []

  await mkdir(directoryUrl, { recursive: true })

  for (const [index, exportedFile] of sourceMedia.entries()) {
    const exportedType = getExportedMediaType(exportedFile)
    const type = exportedType === 'photo' ? 'image' : 'video'
    const extension = extname(exportedFile).toLowerCase()
    const file = `${type}-${index + 1}${extension}`
    const sourceFile = join(mediaPath, exportedFile)
    const destinationFile = new URL(file, directoryUrl)
    const item = {
      alt: extendedMedia[index]?.ext_alt_text ?? '',
      file,
      type,
    }

    await cp(sourceFile, destinationFile)
    copiedMediaCount += 1

    if (type === 'video') {
      const poster = `video-${index + 1}-poster.png`

      if (generateVideoPoster(destinationFile, new URL(poster, directoryUrl))) {
        item.poster = poster
        generatedPosterCount += 1
      }
    }

    media.push(item)
  }

  if (media.length !== post.media.length) {
    throw new Error(
      `Expected ${post.media.length} media file(s) for post ${post.id}, found ${media.length}.`,
    )
  }

  await writeFile(
    new URL('index.md', directoryUrl),
    serializeMoment({
      media,
      publishedAt,
      sourceUrl: post.url,
      text: normalizeText(post),
    }),
  )
}

console.log(
  `Imported ${authoredPosts.length} moment(s) with ${copiedMediaCount} media file(s) and ${generatedPosterCount} video poster(s).`,
)

function isAuthoredPost(post) {
  return post
    && typeof post === 'object'
    && typeof post.id === 'string'
    && typeof post.created_at === 'string'
    && typeof post.full_text === 'string'
    && typeof post.screen_name === 'string'
    && Array.isArray(post.media)
    && post.retweeted_status === null
}

function normalizeDate(value) {
  const match = value.match(
    /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) ([+-]\d{2}):(\d{2})$/,
  )

  if (!match) {
    throw new Error(`Unsupported post date: ${value}`)
  }

  return `${match[1]}T${match[2]}${match[3]}:${match[4]}`
}

function normalizeText(post) {
  let text = post.full_text
  const mediaUrls = new Set(post.media.map(item => item.url).filter(Boolean))
  const urls = post.metadata?.legacy?.entities?.urls ?? []

  for (const url of mediaUrls) {
    text = text.replaceAll(url, '')
  }

  for (const url of urls) {
    if (url.url && url.expanded_url) {
      text = text.replaceAll(url.url, url.expanded_url)
    }
  }

  return text
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function getExportedMediaType(file) {
  const match = basename(file).match(/_(photo|video|animated_gif)_\d+_\d{8}\.[^.]+$/)

  if (!match) {
    throw new Error(`Unsupported exported media filename: ${file}`)
  }

  return match[1]
}

function compareExportedMedia(first, second) {
  const firstIndex = Number.parseInt(first.match(/_(\d+)_\d{8}\.[^.]+$/)?.[1] ?? '0', 10)
  const secondIndex = Number.parseInt(second.match(/_(\d+)_\d{8}\.[^.]+$/)?.[1] ?? '0', 10)

  return firstIndex - secondIndex || first.localeCompare(second)
}
