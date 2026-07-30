import { createHash } from 'node:crypto'
import { access, mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { basename, join, relative, resolve, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

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
const { uploadR2Object } = await import(
  pathToFileURL(join(repositoryPath, 'scripts/lib/r2-assets.mjs'))
)
const config = JSON.parse(await readFile(configPath, 'utf8'))
const inputs = await Promise.all(options.images.map(async (imagePath) => {
  const path = resolve(imagePath)
  const metadata = await stat(path)

  if (!metadata.isFile() || metadata.size === 0) {
    throw new Error(`Image is missing or empty: ${path}`)
  }

  return path
}))

await assertMissing(targetPath)
await mkdir(targetPath, { recursive: true })

const temporaryPath = await mkdtemp(join(tmpdir(), 'hyoban-moment-media-'))

try {
  const prepared = []

  for (const [index, inputPath] of inputs.entries()) {
    const file = `${slug}${index === 0 ? '' : `-${index + 1}`}.webp`
    const outputPath = join(temporaryPath, file)
    const key = `moments/${targetId}/${file}`

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
    const contents = await readFile(outputPath)

    const uploaded = await uploadR2Object({
      bucket: config.bucket,
      file: outputPath,
      key,
      origin: config.origin,
      root: repositoryPath,
    })

    prepared.push({
      bytes: contents.byteLength,
      contentType: 'image/webp',
      etag: createHash('md5').update(contents).digest('hex'),
      file,
      height: info.height,
      source: basename(inputPath),
      url: uploaded.url,
      width: info.width,
    })
  }

  await writeAssets(prepared)

  console.log(JSON.stringify({ images: prepared, target: targetPath }, null, 2))
} catch (error) {
  await rm(targetPath, { force: true, recursive: true })
  throw error
} finally {
  await rm(temporaryPath, { force: true, recursive: true })
}

async function writeAssets(images) {
  const assets = Object.fromEntries(
    images.map(image => [
      image.file,
      {
        bytes: image.bytes,
        contentType: image.contentType,
        etag: image.etag,
        height: image.height,
        width: image.width,
      },
    ]),
  )

  await writeFile(
    join(targetPath, 'assets.json'),
    `${JSON.stringify(assets, null, 2)}\n`,
  )
}

async function assertMissing(path) {
  try {
    await access(path)
  } catch (error) {
    if (error.code === 'ENOENT') {
      return
    }

    throw error
  }

  throw new Error(`Moment target already exists: ${path}`)
}

function parseArguments(argv) {
  const separator = argv.indexOf('--')
  const optionArguments = separator === -1 ? argv : argv.slice(0, separator)
  const images = separator === -1 ? [] : argv.slice(separator + 1)
  const values = {}

  for (let index = 0; index < optionArguments.length; index += 2) {
    const flag = optionArguments[index]
    const value = optionArguments[index + 1]

    if ((flag !== '--repo' && flag !== '--target') || !value) {
      throw new Error('Usage: prepare-images.mjs --repo <path> --target <path> -- <images...>')
    }

    values[flag.slice(2)] = value
  }

  if (!values.repo || !values.target || images.length === 0) {
    throw new Error('Usage: prepare-images.mjs --repo <path> --target <path> -- <images...>')
  }

  return { ...values, images }
}
