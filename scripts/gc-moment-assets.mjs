import { createHash } from 'node:crypto'
import { execFile as execFileCallback } from 'node:child_process'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

import { listR2Objects } from './lib/cloudflare-r2-api.mjs'
import { getReferencedR2Objects, readMomentRecords } from './lib/moment-assets.mjs'

const execFile = promisify(execFileCallback)
const root = fileURLToPath(new URL('../', import.meta.url))
const contentRoot = join(root, 'src/content/moments')
const config = JSON.parse(await readFile(join(root, 'src/data/asset-config.json'), 'utf8'))
const options = parseArguments(process.argv.slice(2))

if (options.mode === 'dry-run') {
  await createManifest()
} else {
  await applyManifest()
}

async function createManifest() {
  const generatedAt = new Date()
  const cutoff = new Date(generatedAt.getTime() - options.graceDays * 24 * 60 * 60 * 1000)
  const records = await readMomentRecords(contentRoot)
  const referenced = new Set(getReferencedR2Objects(records).map(object => object.key))
  const inventory = await listR2Objects({
    accountId: config.accountId,
    bucket: config.bucket,
    prefix: 'moments/',
    root,
  })
  const candidates = inventory
    .filter(object => !referenced.has(object.key))
    .map(object => ({
      bytes: object.size,
      eligible: new Date(object.last_modified) <= cutoff,
      etag: object.etag,
      key: object.key,
      lastModified: object.last_modified,
    }))
    .sort((first, second) => first.key.localeCompare(second.key))
  const eligible = candidates.filter(candidate => candidate.eligible)
  const manifest = {
    version: 1,
    bucket: config.bucket,
    prefix: 'moments/',
    generatedAt: generatedAt.toISOString(),
    graceDays: options.graceDays,
    inventoryObjects: inventory.length,
    referencedObjects: referenced.size,
    candidates,
    eligibleDigest: digestCandidates(eligible),
  }
  const output = resolve(
    options.output
    ?? join(root, '.artifacts', `moments-r2-gc-${generatedAt.toISOString().slice(0, 10)}.json`),
  )

  await mkdir(dirname(output), { recursive: true })
  await writeFile(output, `${JSON.stringify(manifest, null, 2)}\n`)

  const candidateBytes = candidates.reduce((sum, candidate) => sum + candidate.bytes, 0)
  const eligibleBytes = eligible.reduce((sum, candidate) => sum + candidate.bytes, 0)

  console.log(
    `R2 GC dry run: ${inventory.length} inventoried, ${referenced.size} referenced, `
    + `${candidates.length} unreferenced (${formatBytes(candidateBytes)}), `
    + `${eligible.length} past ${options.graceDays}-day grace (${formatBytes(eligibleBytes)}).`,
  )
  console.log(`Manifest: ${output}`)
  console.log('No objects were deleted.')
}

async function applyManifest() {
  const manifestPath = resolve(options.manifest)
  const manifest = JSON.parse(await readFile(manifestPath, 'utf8'))

  if (
    manifest.version !== 1
    || manifest.bucket !== config.bucket
    || manifest.prefix !== 'moments/'
    || !Array.isArray(manifest.candidates)
  ) {
    throw new Error(`Invalid R2 GC manifest: ${manifestPath}`)
  }

  const candidates = manifest.candidates.filter(candidate => candidate.eligible)

  if (digestCandidates(candidates) !== manifest.eligibleDigest) {
    throw new Error('R2 GC manifest digest mismatch.')
  }

  const records = await readMomentRecords(contentRoot)
  const referenced = new Set(getReferencedR2Objects(records).map(object => object.key))
  const inventory = await listR2Objects({
    accountId: config.accountId,
    bucket: config.bucket,
    prefix: 'moments/',
    root,
  })
  const remoteByKey = new Map(inventory.map(object => [object.key, object]))
  const cutoff = new Date(
    new Date(manifest.generatedAt).getTime() - manifest.graceDays * 24 * 60 * 60 * 1000,
  )

  for (const candidate of candidates) {
    const remote = remoteByKey.get(candidate.key)

    if (referenced.has(candidate.key)) {
      throw new Error(`Refusing to delete a now-referenced object: ${candidate.key}`)
    }

    if (
      !remote
      || remote.etag !== candidate.etag
      || remote.size !== candidate.bytes
      || remote.last_modified !== candidate.lastModified
      || new Date(remote.last_modified) > cutoff
    ) {
      throw new Error(`R2 object changed since manifest creation: ${candidate.key}`)
    }
  }

  const wrangler = join(root, 'node_modules', '.bin', 'wrangler')

  let completed = 0

  await runConcurrent(candidates, 8, async (candidate) => {
    await execFile(wrangler, [
      'r2',
      'object',
      'delete',
      `${config.bucket}/${candidate.key}`,
      '--remote',
      '--force',
    ], {
      cwd: root,
      maxBuffer: 10 * 1024 * 1024,
    })

    completed += 1

    if (completed % 50 === 0 || completed === candidates.length) {
      console.log(`Deleted ${completed}/${candidates.length} verified object(s).`)
    }
  })

  console.log(`R2 GC applied: ${candidates.length} object(s) deleted from ${config.bucket}.`)
}

async function runConcurrent(items, concurrency, operation) {
  let nextIndex = 0

  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (nextIndex < items.length) {
      const index = nextIndex
      nextIndex += 1
      await operation(items[index], index)
    }
  }))
}

function digestCandidates(candidates) {
  return createHash('sha256')
    .update(JSON.stringify(candidates))
    .digest('hex')
}

function formatBytes(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(2)} MiB`
}

function parseArguments(argv) {
  if (argv[0] === '--apply' && argv[1]) {
    if (argv.length !== 2) {
      throw new Error('Usage: gc-moment-assets.mjs --apply <manifest.json>')
    }

    return { manifest: argv[1], mode: 'apply' }
  }

  const options = {
    graceDays: 30,
    mode: 'dry-run',
    output: undefined,
  }

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]

    if (argument === '--dry-run') {
      continue
    }

    if (argument === '--grace-days' && /^\d+$/.test(argv[index + 1] ?? '')) {
      options.graceDays = Number.parseInt(argv[index + 1], 10)
      index += 1
      continue
    }

    if (argument === '--output' && argv[index + 1]) {
      options.output = argv[index + 1]
      index += 1
      continue
    }

    throw new Error(
      'Usage: gc-moment-assets.mjs [--dry-run] [--grace-days <days>] [--output <file>]'
      + ' | --apply <manifest.json>',
    )
  }

  return options
}
