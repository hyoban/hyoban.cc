import { PUBLICATION_PLATFORMS } from './publication-platforms.mjs'

export { PUBLICATION_PLATFORMS }

export class PublicationOutcomeUnknownError extends Error {
  constructor(message) {
    super(message)
    this.name = 'PublicationOutcomeUnknownError'
  }
}

export async function previewPublication(options) {
  if (options.moment.hidden) {
    throw new Error('Hidden Moments cannot be published.')
  }

  const targets = options.targets ?? PUBLICATION_PLATFORMS
  const platforms = []
  let executable = true

  for (const platform of targets) {
    const adapter = options.adapters[platform]
    const receipt = options.receipts[platform]

    if (!adapter) {
      throw new Error(`Missing Publication adapter: ${platform}`)
    }

    if (receipt?.status === 'published' || receipt?.status === 'unknown') {
      platforms.push({ platform, receipt, status: receipt.status })
      continue
    }

    try {
      platforms.push({
        payload: await adapter.adapt(options.moment),
        platform,
        status: 'ready',
      })
    } catch (error) {
      executable = false
      platforms.push({
        error: error instanceof Error ? error.message : String(error),
        platform,
        status: 'invalid',
      })
    }
  }

  return {
    executable,
    momentId: options.moment.id,
    platforms,
  }
}

export async function executePublication(options) {
  if (!options.preview.executable) {
    throw new Error('Publication Preview must be valid before execution.')
  }

  const platforms = []
  let successful = true

  for (const item of options.preview.platforms) {
    if (item.status === 'published' || item.status === 'unknown') {
      if (item.status === 'unknown') {
        successful = false
      }
      platforms.push(item)
      continue
    }

    const timestamp = (options.now?.() ?? new Date()).toISOString()

    try {
      const published = await options.adapters[item.platform].publish(item.payload)
      const receipt = {
        externalId: published.externalId,
        publishedAt: timestamp,
        status: 'published',
        url: published.url,
      }

      await options.writeReceipt(item.platform, receipt)
      platforms.push({ platform: item.platform, receipt, status: 'published' })
    } catch (error) {
      successful = false
      const receipt = {
        attemptedAt: timestamp,
        error: error instanceof Error ? error.message : String(error),
        status: error instanceof PublicationOutcomeUnknownError ? 'unknown' : 'failed',
      }

      await options.writeReceipt(item.platform, receipt)
      platforms.push({ platform: item.platform, receipt, status: receipt.status })
    }
  }

  return { platforms, successful }
}
