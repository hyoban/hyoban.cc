import {
  formatPublicationText,
  normalizePublishedResult,
  normalizeResult,
  OPENCLI_OPTIONS,
  runOpenCliWrite,
} from './shared.mjs'

const TELEGRAM_CHANNEL = 'hyoban_travel'
const TELEGRAM_PEER = '-1003981320482'

export function createTelegramPublicationAdapter(options) {
  return {
    adapt(moment) {
      if (moment.media.length > 10) {
        throw new Error('Telegram supports at most 10 media attachments per Publication.')
      }

      const text = formatPublicationText(moment)
      const limit = moment.media.length > 0 ? 1_024 : 4_096

      if ([...text].length > limit) {
        const contentType = moment.media.length > 0 ? 'media captions' : 'messages'
        throw new Error(`Telegram ${contentType} support at most ${limit} characters.`)
      }

      if (!text && moment.media.length === 0) {
        throw new Error('Telegram Publication requires text or media.')
      }

      return {
        media: moment.media.map(item => item.sourcePath),
        text,
      }
    },
    async publish(payload) {
      const args = ['telegram', 'publish', payload.text]

      if (payload.media.length > 0) {
        args.push('--media', payload.media.join(','))
      }

      const result = normalizeResult(await runOpenCliWrite(options, [
        ...args,
        '--channel',
        TELEGRAM_CHANNEL,
        '--peer',
        TELEGRAM_PEER,
        '--execute',
        ...OPENCLI_OPTIONS,
      ], 'Telegram'))

      return normalizePublishedResult(result, 'Telegram')
    },
  }
}
