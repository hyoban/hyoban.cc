import { createHash } from 'node:crypto'
import { execFile as execFileCallback } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { basename, join } from 'node:path'
import { promisify } from 'node:util'

import ffmpeg from 'ffmpeg-static'
import sharp from 'sharp'

import { getContentType, uploadR2Object } from './r2-assets.mjs'

// Turns a Moment's source media into what the site serves: WebP stills with 480 and
// 960 pixel variants, H.264 videos with a poster, and a published block for the record
// naming each object by its key under the Moment's id. The create-moment skill's.

const execFile = promisify(execFileCallback)

/** An image: the optimized still and its variants. */
export async function prepareImage({ inputPath, momentId, stem, temporaryPath }) {
  const outputPath = join(temporaryPath, `${stem}.webp`)
  const info = await sharp(inputPath)
    .rotate()
    .resize({ fit: 'inside', height: 1920, width: 1920, withoutEnlargement: true })
    .webp({ effort: 6, quality: 82 })
    .toFile(outputPath)
  const still = await describeAsset(outputPath, momentId, {
    height: info.height,
    width: info.width,
  })
  const variants = []

  for (const width of [480, 960]) {
    if (width >= info.width) {
      continue
    }

    const variantPath = join(temporaryPath, `${stem}-${width}w.webp`)
    const variantInfo = await sharp(outputPath)
      .resize({ width, withoutEnlargement: true })
      .webp({ effort: 6, quality: 80 })
      .toFile(variantPath)
    variants.push(
      await describeAsset(variantPath, momentId, {
        height: variantInfo.height,
        width: variantInfo.width,
      }),
    )
  }

  return {
    assets: [still, ...variants],
    published: { ...still.published, variants: variants.map((variant) => variant.published) },
  }
}

/** A video: transcoded, with its first frame as the poster. */
export async function prepareVideo({ inputPath, momentId, stem, temporaryPath }) {
  const outputPath = join(temporaryPath, `${stem}.mp4`)
  const framePath = join(temporaryPath, `${stem}-frame.png`)
  const posterPath = join(temporaryPath, `${stem}-poster.webp`)

  await transcodeVideo(inputPath, outputPath)
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
  const video = await describeAsset(outputPath, momentId)
  const poster = await describeAsset(posterPath, momentId, {
    height: posterInfo.height,
    width: posterInfo.width,
  })

  return {
    assets: [video, poster],
    published: { ...video.published, poster: { ...poster.published, variants: [] } },
  }
}

/** A live photo: the still as an image, and its motion transcoded without a poster. */
export async function prepareLivePhoto({ inputPath, momentId, stem, temporaryPath, videoPath }) {
  const still = await prepareImage({ inputPath, momentId, stem, temporaryPath })
  // Beside its still, as `getMediaKey` names a live photo's video.
  const outputPath = join(temporaryPath, `${stem}-video.mp4`)

  await transcodeVideo(videoPath, outputPath)
  const video = await describeAsset(outputPath, momentId)

  return {
    assets: [...still.assets, video],
    published: { ...still.published, video: video.published },
  }
}

/** Uploads prepared assets, reusing identical objects already in the bucket. */
export async function uploadAssets(assets, { config, root }) {
  const actions = {}

  for (const asset of assets) {
    const upload = await uploadR2Object({
      bucket: config.bucket,
      file: asset.path,
      key: asset.published.key,
      origin: config.origin,
      root,
    })
    actions[basename(asset.published.key)] = upload.action
  }

  return actions
}

async function transcodeVideo(inputPath, outputPath) {
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
}

/** A prepared file's key under the Moment and the metadata the record publishes. */
async function describeAsset(path, momentId, dimensions = {}) {
  const contents = await readFile(path)
  const key = `moments/${momentId}/${basename(path)}`

  return {
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
