import {
  formatPublicationText,
  normalizePublishedResult,
  normalizeResult,
  OPENCLI_OPTIONS,
  runOpenCliWrite,
} from './shared.mjs'

export function createXPublicationAdapter(options) {
  return {
    adapt(moment) {
      if (moment.media.some(item => item.type === 'video')) {
        throw new Error('X video Publication is not supported by the OpenCLI adapter.')
      }

      if (moment.media.length > 4) {
        throw new Error('X supports at most 4 images per Publication.')
      }

      const text = formatPublicationText(moment)
      const weightedLength = [...text]
        .reduce((length, character) => length + (character.codePointAt(0) <= 0x7F ? 1 : 2), 0)

      if (weightedLength > 280) {
        throw new Error('X Publications support at most 280 weighted characters.')
      }

      if (!text && moment.media.length === 0) {
        throw new Error('X Publication requires text or media.')
      }

      return {
        images: moment.media.map(item => item.sourcePath),
        text,
      }
    },
    async publish(payload) {
      const args = ['twitter', 'post', payload.text]

      if (payload.images.length > 0) {
        args.push('--images', payload.images.join(','))
      }

      const result = normalizeResult(await runOpenCliWrite(options, [
        ...args,
        ...OPENCLI_OPTIONS,
      ], 'X'))

      return normalizePublishedResult(result, 'X')
    },
  }
}
