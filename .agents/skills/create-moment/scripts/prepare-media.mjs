import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

// Prepares a Moment's media for the site: optimizes images with responsive variants,
// transcodes videos with a poster, uploads everything to R2 under the Moment's id, and
// writes the Moment's record under data/moments with its media published and its words
// and alt text left for the skill to fill in.

const options = parseArguments(process.argv.slice(2))
const repositoryPath = resolve(options.repo)
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
const fromRepository = (file) => import(pathToFileURL(join(repositoryPath, file)))
const { getContentType } = await fromRepository('scripts/lib/r2-assets.mjs')
const { prepareImage, prepareVideo, uploadAssets } = await fromRepository(
  'scripts/lib/moment-media.mjs',
)
const { ulid } = await fromRepository('scripts/lib/ulid.mjs')
const { getMomentFile, momentRecordSchema } = await fromRepository('src/data/spec.ts')
const { readMomentRecords } = await fromRepository('src/data/store.ts')
const config = JSON.parse(
  await readFile(join(repositoryPath, 'src/data/asset-config.json'), 'utf8'),
)
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
  const assets = []
  const media = []
  let imageIndex = 0
  let videoIndex = 0

  for (const input of inputs) {
    const isImage = input.contentType.startsWith('image/')
    const index = isImage ? (imageIndex += 1) : (videoIndex += 1)
    const stem = `${slug}${isImage ? '' : '-video'}${index === 1 ? '' : `-${index}`}`
    const prepared = await (isImage ? prepareImage : prepareVideo)({
      inputPath: input.path,
      momentId: options.id,
      stem,
      temporaryPath,
    })
    const size = isImage ? prepared.published : prepared.published.poster
    assets.push(...prepared.assets)
    media.push({
      alt: '',
      height: size.height,
      published: prepared.published,
      source: basename(input.path),
      type: isImage ? 'image' : 'video',
      width: size.width,
    })
  }

  const uploads = await uploadAssets(assets, { config, root: repositoryPath })
  const { action, file, uid } = await writeRecord(media)

  console.log(
    JSON.stringify(
      {
        action,
        file,
        media: media.map(({ published, source, type }) => ({ key: published.key, source, type })),
        uid,
        uploads,
      },
      null,
      2,
    ),
  )

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
