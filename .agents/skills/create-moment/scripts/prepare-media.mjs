import { createHash } from 'node:crypto'
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

// Prepares a Moment's media for the site: optimizes images with responsive variants,
// transcodes videos with a poster, uploads everything to R2 under the Moment's uid
// (`getMediaKey`), and writes the Moment's record under data/moments with its media
// published and its words and alt text left for the skill to fill in. `--uid` resumes an
// interrupted run with the uid it printed first: the media's uids follow from it, so
// their files have the same keys and the uploads are reused.

const ULID_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const options = parseArguments(process.argv.slice(2))
const repositoryPath = resolve(options.repo)
const fromRepository = (file) => import(pathToFileURL(join(repositoryPath, file)))
const { getContentType } = await fromRepository('scripts/lib/r2-assets.mjs')
const { prepareImage, prepareVideo, uploadAssets } = await fromRepository(
  'scripts/lib/moment-media.mjs',
)
const { ulid } = await fromRepository('scripts/lib/ulid.mjs')
const { momentRecordSchema } = await fromRepository('packages/data-spec/src/index.ts')
const { entrySchema, getEntryFile, momentToEntry } = await fromRepository(
  'packages/data-spec/src/entry.ts',
)
const { readMomentRecords } = await fromRepository('apps/web/src/data/store.ts')
const config = JSON.parse(
  await readFile(join(repositoryPath, 'apps/web/src/data/asset-config.json'), 'utf8'),
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
const time = Date.parse(
  options.occurredAt.length > 10 ? options.occurredAt : `${options.occurredAt}T00:00:00+08:00`,
)
const uid = options.uid ?? ulid(time)
const existing = readMomentRecords().find(({ record }) => record.uid === uid)
// Each media item's uid: its time after the Moment's, and the rest from the Moment's uid.
const mediaUids = inputs.map((_input, index) => {
  const random = createHash('sha256').update(`${uid}/${index}`).digest()
  const suffix = Array.from(random.subarray(0, 16), (byte) => ULID_ALPHABET[byte % 32]).join('')
  return `${ulid(time + index + 1).slice(0, 10)}${suffix}`
})

console.error(`Preparing Moment ${uid}; rerun with --uid ${uid} to resume.`)
const temporaryPath = await mkdtemp(join(tmpdir(), 'hyoban-moment-media-'))

try {
  const assets = []
  const media = []

  for (const [index, input] of inputs.entries()) {
    const isImage = input.contentType.startsWith('image/')
    // Each file is named by the media item's uid in the Moment's directory, as
    // `getMediaKey` names it.
    const prepared = await (isImage ? prepareImage : prepareVideo)({
      inputPath: input.path,
      momentId: uid.toLowerCase(),
      stem: mediaUids[index].toLowerCase(),
      temporaryPath,
    })
    const size = isImage ? prepared.published : prepared.published.poster
    assets.push(...prepared.assets)
    media.push({
      uid: mediaUids[index],
      alt: '',
      height: size.height,
      published: prepared.published,
      source: basename(input.path),
      type: isImage ? 'image' : 'video',
      width: size.width,
    })
  }

  const uploads = await uploadAssets(assets, { config, root: repositoryPath })
  const { action, file } = await writeRecord(media)

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

  /** Writes the Moment's entry, or leaves an identical one from an interrupted run alone. */
  async function writeRecord(media) {
    const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
    const record = momentRecordSchema.parse({
      uid,
      occurredAt: options.occurredAt,
      places: options.places,
      text: '',
      hidden: false,
      media: media.map(({ source: _source, ...item }) => item),
      published: { at: now },
      createdAt: now,
      updatedAt: now,
    })

    if (existing) {
      const publishedKeys = (item) =>
        JSON.stringify(item.media.map(({ published }) => [published.key, published.etag]))

      if (publishedKeys(existing.record) !== publishedKeys(record)) {
        throw new Error(`Moment ${uid} already exists with different media: ${existing.file}`)
      }

      return { action: 'reused', file: existing.file }
    }

    // Written in the second shape, an entry with its words and photos as parts.
    const entry = entrySchema.parse(momentToEntry(record))
    const file = getEntryFile(entry)
    const path = join(repositoryPath, 'data', file)
    await mkdir(dirname(path), { recursive: true })
    await writeFile(path, `${JSON.stringify(momentToEntry(record), null, 2)}\n`)
    return { action: 'created', file }
  }
} finally {
  await rm(temporaryPath, { force: true, recursive: true })
}

function parseArguments(argv) {
  const separator = argv.indexOf('--')
  const optionArguments = separator === -1 ? argv : argv.slice(0, separator)
  const media = separator === -1 ? [] : argv.slice(separator + 1)
  const usage =
    'Usage: prepare-media.mjs --repo <path> --occurred-at <when> [--place <id>] [--uid <ulid>] -- <media...>'
  const values = { places: [] }

  for (let index = 0; index < optionArguments.length; index += 2) {
    const flag = optionArguments[index]
    const value = optionArguments[index + 1]

    if (!value) {
      throw new Error(usage)
    }

    if (flag === '--repo') {
      values.repo = value
    } else if (flag === '--uid') {
      values.uid = value
    } else if (flag === '--occurred-at') {
      values.occurredAt = value
    } else if (flag === '--place') {
      if (values.places.length > 0) {
        throw new Error('A Moment is at one place at most: write one Moment for each place.')
      }
      values.places.push(value)
    } else {
      throw new Error(usage)
    }
  }

  if (!values.repo || !values.occurredAt || media.length === 0) {
    throw new Error(usage)
  }

  return { ...values, media }
}
