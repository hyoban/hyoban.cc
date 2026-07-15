import {
  readMomentForPublication,
  readPublicationReceipts,
  writePublicationReceipt,
} from './moment-repository.mjs'
import { createOpenCliPublicationAdapters } from './opencli-publication-adapters.mjs'
import {
  executePublication,
  previewPublication,
  PUBLICATION_PLATFORMS,
} from './publication.mjs'

export function parsePublicationArguments(argv) {
  const momentIds = []
  const targets = []
  let execute = false

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]

    if (argument === '--execute') {
      execute = true
      continue
    }

    if (argument === '--platform') {
      const platform = argv[index + 1]

      if (!platform || platform.startsWith('-')) {
        throw new Error('--platform requires a Platform name.')
      }

      if (!PUBLICATION_PLATFORMS.includes(platform)) {
        throw new Error(`Unknown Platform: ${platform}`)
      }

      if (!targets.includes(platform)) {
        targets.push(platform)
      }
      index += 1
      continue
    }

    if (argument.startsWith('-')) {
      throw new Error(`Unknown option: ${argument}`)
    }

    momentIds.push(argument)
  }

  if (momentIds.length !== 1) {
    throw new Error('Publication requires exactly one Moment id.')
  }

  return {
    execute,
    momentId: momentIds[0],
    targets: targets.length > 0 ? targets : [...PUBLICATION_PLATFORMS],
  }
}

export async function runPublicationCommand(options) {
  const command = parsePublicationArguments(options.argv)
  const moment = await readMomentForPublication(options.root, command.momentId)
  const receipts = await readPublicationReceipts(options.root, command.momentId)
  const adapters = createOpenCliPublicationAdapters({ runOpenCli: options.runOpenCli })
  const preview = await previewPublication({
    adapters,
    moment,
    receipts,
    targets: command.targets,
  })

  if (!command.execute) {
    return { mode: 'preview', ...preview }
  }

  if (!preview.executable) {
    throw new Error('Publication Preview contains invalid Platform adaptations.')
  }

  const execution = await executePublication({
    adapters,
    now: options.now,
    preview,
    writeReceipt: (platform, receipt) => writePublicationReceipt(
      options.root,
      command.momentId,
      platform,
      receipt,
    ),
  })

  return {
    mode: 'execute',
    momentId: command.momentId,
    ...execution,
  }
}
