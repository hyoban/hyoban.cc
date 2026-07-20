import { access, mkdir, rename, rm, stat } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { basename, join, relative, resolve, sep } from 'node:path'

const options = parseArguments(process.argv.slice(2))
const repositoryPath = resolve(options.repo)
const momentsPath = join(repositoryPath, 'src/content/moments')
const targetPath = resolve(options.target)
const targetId = relative(momentsPath, targetPath).split(sep).join('/')

if (!/^\d{4}\/\d{2}\/[^/]+$/.test(targetId)) {
  throw new Error(`Target must be one Moment directory inside ${momentsPath}`)
}

const requireFromRepository = createRequire(join(repositoryPath, 'package.json'))
const sharp = requireFromRepository('sharp')
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

const temporaryPaths = []

try {
  const prepared = []

  for (const [index, inputPath] of inputs.entries()) {
    const file = `image-${index + 1}.webp`
    const temporaryPath = join(targetPath, `.${file}.${process.pid}.tmp`)
    temporaryPaths.push(temporaryPath)

    const info = await sharp(inputPath)
      .rotate()
      .resize({
        fit: 'inside',
        height: 1920,
        width: 1920,
        withoutEnlargement: true,
      })
      .webp({ effort: 6, quality: 82 })
      .toFile(temporaryPath)

    prepared.push({
      bytes: info.size,
      file,
      height: info.height,
      source: basename(inputPath),
      width: info.width,
    })
  }

  for (const [index, temporaryPath] of temporaryPaths.entries()) {
    await rename(temporaryPath, join(targetPath, `image-${index + 1}.webp`))
  }

  console.log(JSON.stringify({ images: prepared, target: targetPath }, null, 2))
} catch (error) {
  await Promise.all(temporaryPaths.map(path => rm(path, { force: true })))
  throw error
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
