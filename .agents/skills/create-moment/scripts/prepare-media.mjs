import { createHash } from 'node:crypto'
import { execFile as execFileCallback } from 'node:child_process'
import {
  access,
  mkdir,
  mkdtemp,
  readFile,
  rename,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { basename, dirname, extname, join, relative, resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { promisify } from 'node:util'

const execFile = promisify(execFileCallback)
const options = parseArguments(process.argv.slice(2))
const repositoryPath = resolve(options.repo)
const momentsPath = join(repositoryPath, 'src/content/moments')
const targetPath = resolve(options.target)
const targetId = relative(momentsPath, targetPath).split(sep).join('/')
const configPath = join(repositoryPath, 'src/data/asset-config.json')
const targetMatch = targetId.match(/^\d{4}\/\d{2}\/\d{2}-\d{2}-([a-z0-9]+(?:-[a-z0-9]+)*)$/)

if (!targetMatch) {
  throw new Error(`Target must be one Moment directory inside ${momentsPath}`)
}

const slug = targetMatch[1]
const requireFromRepository = createRequire(join(repositoryPath, 'package.json'))
const sharp = requireFromRepository('sharp')
const ffmpeg = requireFromRepository('ffmpeg-static')
const { getContentType, uploadR2Object } = await import(
  pathToFileURL(join(repositoryPath, 'scripts/lib/r2-assets.mjs'))
)
const config = JSON.parse(await readFile(configPath, 'utf8'))
const inputs = await Promise.all(options.media.map(async (mediaPath) => {
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
}))

await mkdir(dirname(targetPath), { recursive: true })
const temporaryPath = await mkdtemp(join(tmpdir(), 'hyoban-moment-media-'))
const stagingPath = await mkdtemp(join(dirname(targetPath), '.moment-preparing-'))

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
        file: image.file,
        source: basename(input.path),
        type: 'image',
      })
    } else {
      videoIndex += 1
      const stem = `${slug}-video${videoIndex === 1 ? '' : `-${videoIndex}`}`
      const video = await prepareVideo(input.path, stem)
      prepared.push(...video.assets)
      media.push({
        alt: '',
        file: video.file,
        poster: video.poster,
        source: basename(input.path),
        type: 'video',
      })
    }
  }

  const assets = Object.fromEntries(
    prepared
      .sort((first, second) => first.file.localeCompare(second.file))
      .map(asset => [asset.file, asset.metadata]),
  )

  for (const asset of prepared) {
    asset.upload = await uploadR2Object({
      bucket: config.bucket,
      file: asset.path,
      key: `moments/${targetId}/${asset.file}`,
      origin: config.origin,
      root: repositoryPath,
    })
  }

  await writeFile(
    join(stagingPath, 'assets.json'),
    `${JSON.stringify(assets, null, 2)}\n`,
  )
  const action = await finalizeTarget(assets)

  console.log(JSON.stringify({
    action,
    media,
    target: targetPath,
    uploads: Object.fromEntries(prepared.map(asset => [asset.file, asset.upload.action])),
  }, null, 2))

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
    const base = await describeAsset(outputPath, {
      height: info.height,
      width: info.width,
    })
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
      variants.push(await describeAsset(variantPath, {
        height: variantInfo.height,
        width: variantInfo.width,
      }))
    }

    if (variants.length > 0) {
      base.metadata.variants = variants.map(variant => variant.file)
    }

    return { assets: [base, ...variants], file }
  }

  async function prepareVideo(inputPath, stem) {
    const file = `${stem}.mp4`
    const outputPath = join(temporaryPath, file)
    const framePath = join(temporaryPath, `${stem}-frame.png`)
    const poster = `${stem}-poster.webp`
    const posterPath = join(temporaryPath, poster)

    await execFile(ffmpeg, [
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
    ], { maxBuffer: 10 * 1024 * 1024 })

    await execFile(ffmpeg, [
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
    ], { maxBuffer: 10 * 1024 * 1024 })

    const posterInfo = await sharp(framePath)
      .webp({ effort: 6, quality: 82 })
      .toFile(posterPath)

    return {
      assets: [
        await describeAsset(outputPath),
        await describeAsset(posterPath, {
          height: posterInfo.height,
          width: posterInfo.width,
        }),
      ],
      file,
      poster,
    }
  }

  async function describeAsset(path, dimensions = {}) {
    const contents = await readFile(path)

    return {
      file: basename(path),
      metadata: {
        bytes: contents.byteLength,
        contentType: getContentType(path),
        etag: createHash('md5').update(contents).digest('hex'),
        ...dimensions,
      },
      path,
    }
  }

  async function finalizeTarget(assets) {
    try {
      await access(targetPath)
    } catch (error) {
      if (error.code !== 'ENOENT') {
        throw error
      }

      await rename(stagingPath, targetPath)
      return 'created'
    }

    try {
      await access(join(targetPath, 'index.md'))
      throw new Error(`Moment target already contains index.md: ${targetPath}`)
    } catch (error) {
      if (error.code !== 'ENOENT') {
        throw error
      }
    }

    const existing = JSON.parse(await readFile(join(targetPath, 'assets.json'), 'utf8'))

    if (JSON.stringify(existing) !== JSON.stringify(assets)) {
      throw new Error(`Moment target has different prepared media: ${targetPath}`)
    }

    return 'reused'
  }
} finally {
  await rm(temporaryPath, { force: true, recursive: true })
  await rm(stagingPath, { force: true, recursive: true })
}

function parseArguments(argv) {
  const separator = argv.indexOf('--')
  const optionArguments = separator === -1 ? argv : argv.slice(0, separator)
  const media = separator === -1 ? [] : argv.slice(separator + 1)
  const values = {}

  for (let index = 0; index < optionArguments.length; index += 2) {
    const flag = optionArguments[index]
    const value = optionArguments[index + 1]

    if ((flag !== '--repo' && flag !== '--target') || !value) {
      throw new Error('Usage: prepare-media.mjs --repo <path> --target <path> -- <media...>')
    }

    values[flag.slice(2)] = value
  }

  if (!values.repo || !values.target || media.length === 0) {
    throw new Error('Usage: prepare-media.mjs --repo <path> --target <path> -- <media...>')
  }

  return { ...values, media }
}
