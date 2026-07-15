import { createTelegramPublicationAdapter } from './opencli-adapters/telegram.mjs'
import { createXiaohongshuPublicationAdapter } from './opencli-adapters/xiaohongshu.mjs'
import { createXPublicationAdapter } from './opencli-adapters/x.mjs'
import { PUBLICATION_PLATFORMS } from './publication-platforms.mjs'
import { PublicationOutcomeUnknownError } from './publication.mjs'

const adapterFactories = {
  telegram: createTelegramPublicationAdapter,
  xiaohongshu: createXiaohongshuPublicationAdapter,
  x: createXPublicationAdapter,
}

export { PublicationOutcomeUnknownError }

export function createOpenCliPublicationAdapters(options) {
  return Object.fromEntries(PUBLICATION_PLATFORMS.map(platform => [
    platform,
    adapterFactories[platform](options),
  ]))
}
