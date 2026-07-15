import { PublicationOutcomeUnknownError } from './publication.mjs'

const OPENCLI_WRITE_OPTIONS = ['--trace', 'retain-on-failure', '-f', 'json']
const TELEGRAM_CHANNEL = 'hyoban_travel'
const TELEGRAM_PEER = '-1003981320482'

export { PublicationOutcomeUnknownError }

export function createOpenCliPublicationAdapters(options) {
  return {
    telegram: {
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
        const args = [
          'telegram',
          'publish',
          payload.text,
        ]

        if (payload.media.length > 0) {
          args.push('--media', payload.media.join(','))
        }

        const result = normalizeResult(await options.runOpenCli([
          ...args,
          '--channel',
          TELEGRAM_CHANNEL,
          '--peer',
          TELEGRAM_PEER,
          '--execute',
          ...OPENCLI_WRITE_OPTIONS,
        ]))

        return normalizePublishedResult(result, 'Telegram')
      },
    },
    xiaohongshu: {
      adapt(moment) {
        if (moment.media.some(item => item.type === 'video')) {
          throw new Error('Xiaohongshu video Publication is not supported by the OpenCLI adapter.')
        }

        if (moment.media.length > 9) {
          throw new Error('Xiaohongshu supports at most 9 images per Publication.')
        }

        const content = formatPublicationText(moment)
        const title = deriveXiaohongshuTitle(content)

        return {
          content,
          images: moment.media.map(item => item.sourcePath),
          title,
        }
      },
      async publish(payload) {
        const publishArgs = [
          'xiaohongshu',
          'publish',
          payload.content,
          '--title',
          payload.title,
        ]

        if (payload.images.length > 0) {
          publishArgs.push('--images', payload.images.join(','))
        } else {
          publishArgs.push('--card-text', payload.content)
        }

        await options.runOpenCli([...publishArgs, ...OPENCLI_WRITE_OPTIONS])
        const notes = await options.runOpenCli([
          'xiaohongshu',
          'creator-notes',
          '--limit',
          '10',
          ...OPENCLI_WRITE_OPTIONS,
        ])
        const note = (Array.isArray(notes) ? notes : [notes])
          .find(item => item?.title === payload.title && item.id && item.url)

        if (!note) {
          throw new PublicationOutcomeUnknownError(
            `Published Xiaohongshu note could not be identified: ${payload.title}`,
          )
        }

        return { externalId: String(note.id), url: note.url }
      },
    },
    x: {
      adapt(moment) {
        if (moment.media.some(item => item.type === 'video')) {
          throw new Error('X video Publication is not supported by the OpenCLI adapter.')
        }

        if (moment.media.length > 4) {
          throw new Error('X supports at most 4 images per Publication.')
        }

        const text = formatPublicationText(moment)

        if ([...text].length > 280) {
          throw new Error('X Publications support at most 280 characters.')
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

        const result = normalizeResult(await options.runOpenCli([
          ...args,
          ...OPENCLI_WRITE_OPTIONS,
        ]))

        return normalizePublishedResult(result, 'X')
      },
    },
  }
}

function deriveXiaohongshuTitle(text) {
  const firstLine = text.split(/\r?\n/).find(line => line.trim())?.trim()

  if (!firstLine) {
    throw new Error('Xiaohongshu Publication requires text.')
  }

  return [...firstLine].slice(0, 20).join('')
}

function normalizeResult(value) {
  return Array.isArray(value) ? value[0] : value
}

function normalizePublishedResult(result, platform) {
  if (result?.status !== 'success' || !result.id || !result.url) {
    throw new PublicationOutcomeUnknownError(
      `${platform} Publication completed without a verifiable external identity.`,
    )
  }

  return { externalId: String(result.id), url: result.url }
}

function formatPublicationText(moment) {
  const descriptions = moment.media
    .map(item => item.alt?.trim())
    .filter(Boolean)
  const text = moment.text.trim()

  if (descriptions.length === 0) {
    return text
  }

  const descriptionBlock = [
    'Media descriptions:',
    ...descriptions.map((description, index) => `${index + 1}. ${description}`),
  ].join('\n')

  return text ? `${text}\n\n${descriptionBlock}` : descriptionBlock
}
