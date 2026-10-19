import { execFile as execFileCallback } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

import { getMomentFile } from '@hyoban/data-spec'
import { pushData, refreshData } from './lib/data-sync.mjs'
import { prepareImage, prepareLivePhoto, prepareVideo, uploadAssets } from './lib/moment-media.mjs'
import { GENERATOR, publishBundle, readBundle, writeReceipt } from './lib/publish-data.mjs'

// pnpm data:publish <bundle.zip | directory> [--local] [--dry-run]
//
// Publishes what the recording app exported: refreshes data/ from the API, unpacks the
// bundle under .artifacts, uploads the new media to R2, writes the records into data/,
// pushes them into the API's database (the local one with --local), and leaves
// receipt.zip beside the unpacked bundle to share back to the app. Nothing is committed.

const execFile = promisify(execFileCallback)
const root = fileURLToPath(new URL('../', import.meta.url))
const options = parseArguments(process.argv.slice(2))
const config = JSON.parse(await readFile(join(root, 'src/data/asset-config.json'), 'utf8'))
const workspace = join(
  root,
  '.artifacts',
  'publish',
  new Date().toISOString().replaceAll(/[:.]/g, '-'),
)
const bundleDirectory = await unpack(options.bundle, join(workspace, 'bundle'))
const bundle = await readBundle(bundleDirectory)

console.log(
  `Bundle from ${bundle.manifest.generator} (${bundle.manifest.updatedAt}): ${Object.keys(bundle.places).length} place(s), ${bundle.trips.length} trip(s), ${bundle.moments.length} Moment(s), media for ${bundle.media.size} item(s).`,
)

if (options.dryRun) {
  const { planPlaces } = await import('./lib/publish-data.mjs')
  const { readPlaces } = await import('../src/data/store.ts')
  const plan = planPlaces(bundle.places, readPlaces())
  console.log(
    JSON.stringify({ conflicts: plan.conflicts, renamedPlaces: plan.renamedPlaces }, null, 2),
  )
  console.log('Dry run: nothing uploaded or written.')
  process.exit(0)
}

const refreshed = await refreshData(root)

console.log(
  `Refreshed data/ from the API at revision ${refreshed.snapshot.revision}${refreshed.changed.length > 0 ? `: ${refreshed.changed.length} files changed` : ''}.`,
)

const temporaryPath = await mkdtemp(join(tmpdir(), 'hyoban-publish-'))

try {
  const result = await publishBundle(bundle, {
    dataRoot: join(root, 'data'),
    prepareMedia: async ({ files, item, momentId, stem }) => {
      const prepared =
        item.type === 'video'
          ? await prepareVideo({ inputPath: files.video, momentId, stem, temporaryPath })
          : item.type === 'live-photo'
            ? await prepareLivePhoto({
                inputPath: files.image,
                momentId,
                stem,
                temporaryPath,
                videoPath: files.video,
              })
            : await prepareImage({ inputPath: files.image, momentId, stem, temporaryPath })

      await uploadAssets(prepared.assets, { config, root })
      return prepared.published
    },
  })
  const receiptDirectory = join(workspace, 'receipt')

  await writeReceipt(result.receipt, receiptDirectory)
  await execFile('zip', ['-q', '-r', join(workspace, 'receipt.zip'), '.'], {
    cwd: receiptDirectory,
  })

  console.log(
    `Published ${result.moments.length} Moment(s) and ${result.trips.length} trip(s) into data/.`,
  )

  const records = {
    moments: result.moments.map((record) => getMomentFile(record)),
    trips: result.trips.map((trip) => `trips/${trip.id}.json`),
    places: Object.keys(result.receipt.places),
  }

  await pushData(root, records, { generator: GENERATOR, remote: !options.local })
  console.log(`Pushed them ${options.local ? 'into the local database' : 'into the API'}.`)

  for (const [from, to] of Object.entries(result.renamedPlaces)) {
    console.log(`Renamed place ${from} to ${to}.`)
  }

  for (const line of [...result.conflicts, ...result.skipped]) {
    console.log(`Warning: ${line}`)
  }

  if (result.blankAlt.length > 0) {
    console.log(`Visible media without alt text:\n  ${result.blankAlt.join('\n  ')}`)
  }

  console.log(`Receipt: ${relative(root, join(workspace, 'receipt.zip'))}`)
  console.log(
    'Next: write the alt texts, run pnpm trips:weather, then pnpm test, pnpm check and pnpm build, and push the records again:',
  )
  console.log(
    `  pnpm data:push${options.local ? ' --local' : ''} ${[...records.moments, ...records.trips].map((file) => `data/${file}`).join(' ')}`,
  )
} finally {
  await rm(temporaryPath, { force: true, recursive: true })
}

/** A bundle as a directory: unzipped into the workspace when it's a zip. */
async function unpack(bundle, directory) {
  const path = resolve(bundle)

  if ((await stat(path)).isDirectory()) {
    return path
  }

  await mkdir(directory, { recursive: true })
  await execFile('unzip', ['-q', path, '-d', directory])
  return directory
}

function parseArguments(argv) {
  const flags = ['--dry-run', '--local']
  const dryRun = argv.includes('--dry-run')
  const local = argv.includes('--local')
  const rest = argv.filter((argument) => !flags.includes(argument))

  if (rest.length !== 1) {
    throw new Error('Usage: pnpm data:publish <bundle.zip | directory> [--local] [--dry-run]')
  }

  return { bundle: rest[0], dryRun, local }
}
