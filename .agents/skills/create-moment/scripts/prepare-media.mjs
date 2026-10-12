import { createHash } from 'node:crypto'
import { execFile as execFileCallback } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { promisify } from 'node:util'

// Prepares a Moment's media for the site: optimizes images with responsive variants,
// transcodes videos with a poster, uploads everything to R2 under the Moment's id, and
// writes the Moment's record under data/moments with its media published and its words,
// places and alt text left for the skill to fill in.

const execFile = promisify(execFileCallback)
const options = parseArguments(process.argv.slice(2))
const repositoryPath = resolve(options.repo)
const configPath = join(repositoryPath, 'src/data/asset-config.json')
const targetMatch = options.id.match(
  /^(\d{4})\/(\d{2})\/(\d{2})-(\d{2})-([a-z0-9]+(?:-[a-z0-9]+)*)$/,
)

if (!targetMatch) {
  throw new Error(`The id must be YYYY/MM/DD-NN-slug, not "${options.id}"`)
}

if (options.occurredAt.slice(0, 10) !== `${targetMatch[1]}-${targetMatch[2]}-${targetMatch[3]}`) {
  throw new Error(`The id "${options.id}" is on another day than ${options.occurredAt}`)
}

const slug = targetMatch[5]
const order = Number.parseInt(targetMatch[4], 10)
const requireFromRepository = createRequire(join(repositoryPath, 'package.json'))
const sharp = requireFromRepository('sharp')
const ffmpeg = requireFromRepository('ffmpeg-static')
const { getContentType, uploadR2Object } = await import(
  pathToFileURL(join(repositoryPath, 'scripts/lib/r2-assets.mjs'))
)
const { ulid } = await import(pathToFileURL(join(repositoryPath, 'scripts/lib/ulid.mjs')))
const { getMomentFile, momentRecordSchema } = await import(
  pathToFileURL(join(repositoryPath, 'src/data/spec.ts'))
)
const { readMomentRecords } = await import(pathToFileURL(join(repositoryPath, 'src/data/store.ts')))
const config = JSON.parse(await readFile(configPath, 'utf8'))
const inputs = await Promise.all(
  options.media.map(async (mediaPath) => {
    const path = resolve(mediaPath)
    const metadata = await stat(path)

    if (!metadata.isFile() || metadata.size === 0) {
      throw new Error(`Media is missing or empty: ${path}`)
    }

    const contentType = getContentType(path)

    if (!contentType.startsWith('image/') && !contentType.startsWith('video/')) {
      throw new Error(`Moment media must be an image or video: ${path}`)
    }

    return { contentType, path }
  }),
)
const existing = readMomentRecords().find(({ record }) => record.published?.id === options.id)
const temporaryPath = await mkdtemp(join(tmpdir(), 'hyoban-moment-media-'))

try {
  const prepared = []
  const media = []
  let imageIndex = 0
  let videoIndex = 0

  for (const input of inputs) {
    if (input.contentType.startsWith('image/')) {
      imageIndex += 1
      const stem = `${slug}${imageIndex === 1 ? '' : `-${imageIndex}`}`
      const image = await prepareImage(input.path, stem)
      prepared.push(...image.assets)
      media.push({
        alt: '',
        published: image.published,
        source: basename(input.path),
        type: 'image',
        width: image.published.width,
        height: image.published.height,
      })
    } else {
      videoIndex += 1
      const stem = `${slug}-video${videoIndex === 1 ? '' : `-${videoIndex}`}`
      const video = await prepareVideo(input.path, stem)
      prepared.push(...video.assets)
      media.push({
        alt: '',
        published: video.published,
        source: basename(input.path),
        type: 'video',
        width: video.published.poster.width,
        height: video.published.poster.height,
      })
    }
  }

  for (const asset of prepared) {
    asset.upload = await uploadR2Object({
      bucket: config.bucket,
      file: asset.path,
      key: asset.key,
      origin: config.origin,
      root: repositoryPath,
    })
  }

  const { action, file, uid } = await writeRecord(media)

  console.log(
    JSON.stringify(
      {
        action,
        file,
        media: media.map(({ source, type, published }) => ({ key: published.key, source, type })),
        uid,
        uploads: Object.fromEntries(
          prepared.map((asset) => [basename(asset.key), asset.upload.action]),
        ),
      },
      null,
      2,
    ),
  )

  async function prepareImage(inputPath, stem) {
    const file = `${stem}.webp`
    const outputPath = join(temporaryPath, file)
    const info = await sharp(inputPath)
      .rotate()
      .resize({
        fit: 'inside',
        height: 1920,
        width: 1920,
        withoutEnlargement: true,
      })
      .webp({ effort: 6, quality: 82 })
      .toFile(outputPath)
    const base = await describeAsset(outputPath, { height: info.height, width: info.width })
    const variants = []

    for (const width of [480, 960]) {
      if (width >= info.width) {
        continue
      }

      const variantFile = `${stem}-${width}w.webp`
      const variantPath = join(temporaryPath, variantFile)
      const variantInfo = await sharp(outputPath)
        .resize({ width, withoutEnlargement: true })
        .webp({ effort: 6, quality: 80 })
        .toFile(variantPath)
      variants.push(
        await describeAsset(variantPath, { height: variantInfo.height, width: variantInfo.width }),
      )
    }

    return {
      assets: [base, ...variants],
      published: { ...base.published, variants: variants.map((variant) => variant.published) },
    }
  }

  async function prepareVideo(inputPath, stem) {
    const file = `${stem}.mp4`
    const outputPath = join(temporaryPath, file)
    const framePath = join(temporaryPath, `${stem}-frame.png`)
    const posterPath = join(temporaryPath, `${stem}-poster.webp`)

    await execFile(
      ffmpeg,
      [
        '-hide_banner',
        '-loglevel',
        'error',
        '-y',
        '-i',
        inputPath,
        '-map',
        '0:v:0',
        '-map',
        '0:a?',
        '-vf',
        'scale=min(1920\\,iw):min(1920\\,ih):force_original_aspect_ratio=decrease:force_divisible_by=2',
        '-c:v',
        'libx264',
        '-crf',
        '23',
        '-preset',
        'medium',
        '-pix_fmt',
        'yuv420p',
        '-c:a',
        'aac',
        '-b:a',
        '160k',
        '-movflags',
        '+faststart',
        outputPath,
      ],
      { maxBuffer: 10 * 1024 * 1024 },
    )

    await execFile(
      ffmpeg,
      [
        '-hide_banner',
        '-loglevel',
        'error',
        '-y',
        '-ss',
        '0',
        '-i',
        outputPath,
        '-frames:v',
        '1',
        framePath,
      ],
      { maxBuffer: 10 * 1024 * 1024 },
    )

    const posterInfo = await sharp(framePath).webp({ effort: 6, quality: 82 }).toFile(posterPath)
    const video = await describeAsset(outputPath)
    const poster = await describeAsset(posterPath, {
      height: posterInfo.height,
      width: posterInfo.width,
    })

    return {
      assets: [video, poster],
      published: { ...video.published, poster: { ...poster.published, variants: [] } },
    }
  }

  /** An optimized file's R2 key and metadata, as the record publishes it. */
  async function describeAsset(path, dimensions = {}) {
    const contents = await readFile(path)
    const key = `moments/${options.id}/${basename(path)}`

    return {
      key,
      path,
      published: {
        key,
        contentType: getContentType(path),
        bytes: contents.byteLength,
        etag: createHash('md5').update(contents).digest('hex'),
        ...dimensions,
      },
    }
  }

  /** Writes the Moment's record, or leaves an identical one from an interrupted run alone. */
  async function writeRecord(media) {
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    const time = Date.parse(
      options.occurredAt.length > 10 ? options.occurredAt : `${options.occurredAt}T00:00:00+08:00`,
    )
    const record = momentRecordSchema.parse({
      uid: ulid(time),
      occurredAt: options.occurredAt,
      places: options.places,
      text: '',
      hidden: false,
      media: media.map(({ source: _source, ...item }, index) => ({
        uid: ulid(time + index + 1),
        ...item,
      })),
      published: { id: options.id, slug, order, at: now },
      createdAt: now,
      updatedAt: now,
    })

    if (existing) {
      const publishedKeys = (item) =>
        JSON.stringify(item.media.map(({ published }) => [published.key, published.etag]))

      if (publishedKeys(existing.record) !== publishedKeys(record)) {
        throw new Error(
          `Moment ${options.id} already exists with different media: ${existing.file}`,
        )
      }

      return { action: 'reused', file: existing.file, uid: existing.record.uid }
    }

    const file = getMomentFile(record)
    const path = join(repositoryPath, 'data', file)
    await mkdir(dirname(path), { recursive: true })
    await writeFile(path, `${JSON.stringify(record, null, 2)}\n`)
    return { action: 'created', file, uid: record.uid }
  }
} finally {
  await rm(temporaryPath, { force: true, recursive: true })
}

function parseArguments(argv) {
  const separator = argv.indexOf('--')
  const optionArguments = separator === -1 ? argv : argv.slice(0, separator)
  const media = separator === -1 ? [] : argv.slice(separator + 1)
  const usage =
    'Usage: prepare-media.mjs --repo <path> --id <YYYY/MM/DD-NN-slug> --occurred-at <when> [--place <id>]... -- <media...>'
  const values = { places: [] }

  for (let index = 0; index < optionArguments.length; index += 2) {
    const flag = optionArguments[index]
    const value = optionArguments[index + 1]

    if (!value) {
      throw new Error(usage)
    }

    if (flag === '--repo') {
      values.repo = value
    } else if (flag === '--id') {
      values.id = value
    } else if (flag === '--occurred-at') {
      values.occurredAt = value
    } else if (flag === '--place') {
      values.places.push(value)
    } else {
      throw new Error(usage)
    }
  }

  if (!values.repo || !values.id || !values.occurredAt || media.length === 0) {
    throw new Error(usage)
  }

  return { ...values, media }
}
