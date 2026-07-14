import { execFile as execFileCallback } from 'node:child_process'
import {
  cp,
  mkdir,
  mkdtemp,
  open,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'
import { promisify } from 'node:util'

import {
  canonicalXiaohongshuSourceUrl,
  composeXiaohongshuText,
  detectXiaohongshuMediaType,
  extractXiaohongshuNoteId,
  parseXiaohongshuNoteFields,
  parseXiaohongshuProfileNotes,
  planXiaohongshuMedia,
  publishedAtFromXiaohongshuNoteId,
  validateXiaohongshuDownload,
} from './lib/xiaohongshu-export.mjs'
import {
  generateVideoPoster,
  parseMomentSourceUrl,
  serializeImportedMoment,
} from './lib/moment-files.mjs'
import { recoverMomentTransactions } from './lib/moment-transactions.mjs'

const execFile = promisify(execFileCallback)
const rootUrl = new URL('../', import.meta.url)
const outputUrl = new URL('src/content/moments/', rootUrl)
const defaultProfile = '64085a45000000000f01228d'
class OpenCliError extends Error {}

const { expectedCount, limit, profile, refresh } = parseArguments(process.argv.slice(2))
const recovery = await recoverMomentTransactions(outputUrl, 'xiaohongshu-')

if (Object.values(recovery).some(count => count > 0)) {
  console.log(
    `Recovered interrupted imports: restored ${recovery.restoredBackups} backup(s), removed ${recovery.removedTemporaries} temporary directory/directories and ${recovery.removedBackups} committed backup(s).`,
  )
}

const existingEntries = await findExistingEntries()
const profileNotes = await fetchProfileNotes(profile, limit, expectedCount)

if (expectedCount !== undefined && profileNotes.length !== expectedCount) {
  throw new Error(
    `Expected ${expectedCount} Xiaohongshu note(s), found ${profileNotes.length}.`,
  )
}

const targets = profileNotes.filter(note => refresh || !existingEntries.has(note.id))

if (targets.length === 0) {
  console.log(`Found ${profileNotes.length} Xiaohongshu note(s); all are already imported.`)
  process.exit(0)
}

console.log(
  `Found ${profileNotes.length} Xiaohongshu note(s); preparing ${targets.length} for import.`,
)

const stagingRoot = await mkdtemp(join(tmpdir(), 'hyoban-xiaohongshu-'))

try {
  const moments = []

  for (const [index, note] of targets.entries()) {
    const moment = await prepareMomentWithRetry(note, stagingRoot)
    moments.push(moment)
    console.log(`Prepared ${index + 1}/${targets.length}: ${note.id}`)
  }

  let importedCount = 0
  let refreshedCount = 0
  let mediaCount = 0
  let posterCount = 0

  for (const moment of moments) {
    const existingDirectory = existingEntries.get(moment.id)
    const existingDocument = existingDirectory
      ? await readExistingDocument(existingDirectory)
      : undefined
    const result = await importMoment(moment, {
      directoryUrl: existingDirectory,
      existingDocument,
      replace: refresh && Boolean(existingDirectory),
    })

    if (result.status === 'refreshed') {
      refreshedCount += 1
    } else if (result.status === 'imported') {
      importedCount += 1
    }

    mediaCount += result.mediaCount
    posterCount += result.posterCount
  }

  console.log(
    `Imported ${importedCount} new and refreshed ${refreshedCount} Xiaohongshu moment(s) with ${mediaCount} media file(s) and ${posterCount} video poster(s).`,
  )
} finally {
  await rm(stagingRoot, { force: true, recursive: true })
}

async function prepareMomentWithRetry(note, stagingRoot) {
  try {
    return await prepareMoment(note, stagingRoot)
  } catch (error) {
    if (!(error instanceof OpenCliError)) {
      throw error
    }

    console.warn(`Refreshing the signed URL for ${note.id} after an OpenCLI failure.`)
    await rm(join(stagingRoot, note.id), { force: true, recursive: true })
    const refreshedNotes = await fetchProfileNotes(profile, limit, expectedCount)
    const refreshedNote = refreshedNotes.find(item => item.id === note.id)

    if (!refreshedNote) {
      throw new Error(`Xiaohongshu note disappeared while importing: ${note.id}`, {
        cause: error,
      })
    }

    return prepareMoment(refreshedNote, stagingRoot)
  }
}

async function prepareMoment(note, stagingRoot) {
  const fields = parseXiaohongshuNoteFields(
    await runOpenCliJson(['xiaohongshu', 'note', note.signedUrl]),
  )
  const downloadDirectory = join(stagingRoot, note.id)

  await rm(downloadDirectory, { force: true, recursive: true })
  const downloadOutput = await runOpenCli([
    'xiaohongshu',
    'download',
    note.signedUrl,
    '--output',
    stagingRoot,
  ])

  const downloadedMedia = await inspectDownloadedMedia(note.id, downloadDirectory)
  validateXiaohongshuDownload(downloadOutput, downloadedMedia)
  const title = fields.title || (fields.content ? '' : note.title)

  return {
    id: note.id,
    media: planXiaohongshuMedia(note.type, downloadedMedia),
    publishedAt: publishedAtFromXiaohongshuNoteId(note.id),
    sourceUrl: canonicalXiaohongshuSourceUrl(note.id),
    text: composeXiaohongshuText(title, fields.content),
  }
}

async function inspectDownloadedMedia(noteId, directory) {
  const entries = await readdir(directory, { withFileTypes: true }).catch(() => [])
  const media = []
  const filenamePattern = new RegExp(`^${noteId}_(\\d+)\\.[^.]+$`)

  for (const entry of entries) {
    if (!entry.isFile()) {
      throw new Error(`Unexpected Xiaohongshu download entry: ${entry.name}`)
    }

    const index = Number.parseInt(entry.name.match(filenamePattern)?.[1] ?? '', 10)

    if (!Number.isSafeInteger(index) || index < 1) {
      throw new Error(`Unexpected Xiaohongshu download filename: ${entry.name}`)
    }

    const sourcePath = join(directory, entry.name)
    const file = await stat(sourcePath)

    if (!file.isFile() || file.size === 0) {
      throw new Error(`Xiaohongshu media file is missing or empty: ${sourcePath}`)
    }

    const handle = await open(sourcePath, 'r')
    const header = Buffer.alloc(32)
    let bytesRead

    try {
      ({ bytesRead } = await handle.read(header, 0, header.length, 0))
    } finally {
      await handle.close()
    }

    media.push({
      ...detectXiaohongshuMediaType(header.subarray(0, bytesRead)),
      index,
      sourcePath,
    })
  }

  return media
}

async function importMoment(moment, options) {
  const [year, month] = moment.publishedAt.slice(0, 10).split('-')
  const defaultDirectoryUrl = new URL(
    `${year}/${month}/xiaohongshu-${moment.id}/`,
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
      const number = index + 1
      const file = `${item.type}-${number}${item.extension}`
      const destinationUrl = new URL(file, temporaryUrl)
      const mediaItem = { alt: '', file, type: item.type }

      await cp(item.sourcePath, destinationUrl)

      if (item.type === 'video') {
        if (item.posterPath) {
          const poster = `video-${number}-poster${item.posterExtension}`
          await cp(item.posterPath, new URL(poster, temporaryUrl))
          mediaItem.poster = poster
        } else {
          const poster = `video-${number}-poster.png`

          if (generateVideoPoster(destinationUrl, new URL(poster, temporaryUrl))) {
            mediaItem.poster = poster
          }
        }
      }

      media.push(mediaItem)
    }

    await writeFile(
      new URL('index.md', temporaryUrl),
      serializeImportedMoment({
        media,
        publishedAt: moment.publishedAt,
        sourceUrl: moment.sourceUrl,
        text: moment.text,
      }, options.existingDocument),
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

async function findExistingEntries() {
  const entries = new Map()
  const files = await readdir(outputUrl, { recursive: true }).catch(() => [])

  for (const file of files) {
    if (!file.endsWith('index.md')
      || file.split(/[\\/]/).some(segment => segment.startsWith('.'))) {
      continue
    }

    const indexUrl = new URL(file, outputUrl)
    const document = await readFile(indexUrl, 'utf8')
    const id = extractXiaohongshuNoteId(parseMomentSourceUrl(document))

    if (!id) {
      continue
    }

    if (entries.has(id)) {
      throw new Error(`Duplicate existing Xiaohongshu moment: ${id}`)
    }

    entries.set(id, new URL('./', indexUrl))
  }

  return entries
}

async function readExistingDocument(directoryUrl) {
  return readFile(new URL('index.md', directoryUrl), 'utf8')
}

async function fetchProfileNotes(profileValue, maximum, expectedMinimum) {
  const adapterNotes = parseXiaohongshuProfileNotes(
    await runOpenCliJson([
      'xiaohongshu',
      'user',
      profileValue,
      '--limit',
      String(maximum),
    ]),
  )

  if (expectedMinimum !== undefined && adapterNotes.length >= expectedMinimum) {
    return adapterNotes
  }

  try {
    const browserNotes = await fetchProfileNotesThroughBrowser(
      profileValue,
      maximum,
      expectedMinimum,
    )
    const merged = new Map(adapterNotes.map(note => [note.id, note]))

    for (const note of browserNotes) {
      merged.set(note.id, note)
    }

    return [...merged.values()]
  } catch (error) {
    console.warn(
      `OpenCLI browser pagination fallback failed; using ${adapterNotes.length} adapter result(s).`,
    )
    console.warn(error instanceof Error ? error.message : String(error))
    return adapterNotes
  }
}

async function fetchProfileNotesThroughBrowser(profileValue, maximum, expectedMinimum) {
  const profileId = normalizeProfileId(profileValue)
  const session = `xhs-import-${process.pid}`

  try {
    await runOpenCliBrowser([
      'browser',
      session,
      'open',
      `https://www.xiaohongshu.com/user/profile/${profileId}`,
      '--window',
      'background',
    ])
    await runOpenCliBrowser(['browser', session, 'state'])

    let count = await readBrowserProfileCount(session)
    let stagnantAttempts = 0

    for (let attempt = 0; attempt < 20; attempt += 1) {
      if (expectedMinimum !== undefined && count >= expectedMinimum) {
        break
      }

      await runOpenCliBrowser([
        'browser',
        session,
        'scroll',
        'down',
        '--amount',
        '5000',
      ])
      await runOpenCliBrowser(['browser', session, 'wait', 'time', '2'])

      const nextCount = await readBrowserProfileCount(session)
      stagnantAttempts = nextCount > count ? 0 : stagnantAttempts + 1
      count = nextCount

      if (stagnantAttempts >= 3) {
        break
      }
    }

    const rows = await runOpenCliBrowserJson([
      'browser',
      session,
      'eval',
      buildBrowserProfileExtractionScript(profileId),
    ])

    return parseXiaohongshuProfileNotes(rows).slice(0, maximum)
  } finally {
    await runOpenCliBrowser(['browser', session, 'close']).catch(() => {})
  }
}

async function readBrowserProfileCount(session) {
  return runOpenCliBrowserJson([
    'browser',
    session,
    'eval',
    `(() => {
      const user = window.__INITIAL_STATE__?.user;
      const groups = user?.notes?._value || user?.notes || [];
      return groups.reduce(
        (count, group) => count + (Array.isArray(group) ? group.length : group ? 1 : 0),
        0,
      );
    })()`,
  ])
}

function buildBrowserProfileExtractionScript(profileId) {
  return `(() => {
    const clean = value => typeof value === 'string'
      ? value.trim()
      : value == null ? '' : String(value).trim();
    const user = window.__INITIAL_STATE__?.user;
    const groups = user?.notes?._value || user?.notes || [];
    const entries = groups.flatMap(group => Array.isArray(group) ? group : group ? [group] : []);
    const seen = new Set();
    const rows = [];

    for (const entry of entries) {
      const card = entry?.noteCard ?? entry?.note_card ?? entry;
      const id = clean(card?.noteId ?? card?.note_id ?? entry?.noteId ?? entry?.note_id ?? entry?.id);

      if (!id || seen.has(id)) continue;

      const token = clean(entry?.xsecToken ?? entry?.xsec_token ?? card?.xsecToken ?? card?.xsec_token);
      const url = new URL('https://www.xiaohongshu.com/user/profile/${profileId}/' + id);
      url.searchParams.set('xsec_token', token);
      url.searchParams.set('xsec_source', 'pc_user');
      seen.add(id);
      rows.push({
        id,
        title: clean(card?.displayTitle ?? card?.display_title ?? card?.title),
        type: clean(card?.type),
        url: url.toString(),
      });
    }

    return rows;
  })()`
}

function normalizeProfileId(value) {
  let profileId

  try {
    const url = new URL(value)
    profileId = url.pathname.match(/\/user\/profile\/([^/]+)/)?.[1] ?? value
  } catch {
    profileId = value.replace(/\/+$/, '').split('/').pop()
  }

  if (!profileId || !/^[a-zA-Z0-9]+$/.test(profileId)) {
    throw new Error(`Unsupported Xiaohongshu profile ID: ${profileId}`)
  }

  return profileId
}

async function runOpenCliJson(args) {
  const output = await runOpenCli(args)

  try {
    return JSON.parse(output)
  } catch (error) {
    throw new Error(`OpenCLI returned invalid JSON for ${args.slice(0, 2).join(' ')}.`, {
      cause: error,
    })
  }
}

async function runOpenCliBrowserJson(args) {
  const output = await runOpenCliBrowser(args)

  try {
    return JSON.parse(output)
  } catch (error) {
    throw new Error(`OpenCLI browser returned invalid JSON for ${args.slice(0, 3).join(' ')}.`, {
      cause: error,
    })
  }
}

async function runOpenCli(args) {
  const commandArgs = [
    ...args,
    '--window',
    'background',
    '--site-session',
    'persistent',
    '-f',
    'json',
  ]

  return executeOpenCli(commandArgs, args.slice(0, 2).join(' '))
}

async function runOpenCliBrowser(args) {
  return executeOpenCli(args, args.slice(0, 3).join(' '))
}

async function executeOpenCli(commandArgs, label) {
  try {
    const { stdout } = await execFile(
      process.env.OPENCLI_BIN ?? 'opencli',
      commandArgs,
      { maxBuffer: 64 * 1024 * 1024 },
    )
    return stdout.trim()
  } catch (error) {
    const details = [error.stderr, error.stdout]
      .filter(value => typeof value === 'string' && value.trim())
      .map(value => value.trim())
      .join('\n')
    throw new OpenCliError(
      `OpenCLI command failed: ${label}${details ? `\n${details}` : ''}`,
      { cause: error },
    )
  }
}

function parseArguments(args) {
  let expectedCount
  let limit = 100
  let profile = defaultProfile
  let refresh = false

  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index]

    if (argument === '--refresh') {
      refresh = true
      continue
    }

    if (argument === '--profile' || argument === '--limit' || argument === '--expected-count') {
      const value = args[index + 1]

      if (!value || value.startsWith('--')) {
        throw new Error(`Missing value for ${argument}.`)
      }

      index += 1

      if (argument === '--profile') {
        profile = value
      } else {
        const number = Number.parseInt(value, 10)

        if (!Number.isSafeInteger(number) || number < 1 || String(number) !== value) {
          throw new Error(`Invalid value for ${argument}: ${value}`)
        }

        if (argument === '--limit') {
          limit = number
        } else {
          expectedCount = number
        }
      }

      continue
    }

    throw new Error(
      'Usage: pnpm moment:import-xiaohongshu [--profile <id-or-url>] [--limit <count>] [--expected-count <count>] [--refresh]',
    )
  }

  return { expectedCount, limit, profile, refresh }
}

async function exists(url) {
  try {
    await stat(url)
    return true
  } catch {
    return false
  }
}
