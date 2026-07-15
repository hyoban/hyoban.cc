import { PublicationOutcomeUnknownError } from '../publication.mjs'
import {
  createUnknownOutcomeError,
  formatPublicationText,
  normalizeResult,
  OPENCLI_OPTIONS,
  runOpenCliWrite,
} from './shared.mjs'

const CREATOR_NOTES_ARGS = [
  'xiaohongshu',
  'creator-notes',
  '--limit',
  '10',
  ...OPENCLI_OPTIONS,
]

export function createXiaohongshuPublicationAdapter(options) {
  return {
    adapt(moment) {
      if (moment.media.some(item => item.type === 'video')) {
        throw new Error('Xiaohongshu video Publication is not supported by the OpenCLI adapter.')
      }

      if (moment.media.length > 9) {
        throw new Error('Xiaohongshu supports at most 9 images per Publication.')
      }

      const content = formatPublicationText(moment)
      const title = deriveTitle(content)

      return {
        content,
        images: moment.media.map(item => item.sourcePath),
        title,
      }
    },
    async publish(payload) {
      const before = normalizeRows(await options.runOpenCli(CREATOR_NOTES_ARGS))
      const existingIds = new Set(before.map(note => String(note?.id ?? '')).filter(Boolean))
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

      const publishResult = normalizeResult(await runOpenCliWrite(options, [
        ...publishArgs,
        ...OPENCLI_OPTIONS,
      ], 'Xiaohongshu'))
      const confirmation = `${publishResult?.status ?? ''} ${publishResult?.detail ?? ''}`

      if (!/success|成功/i.test(confirmation)) {
        throw new PublicationOutcomeUnknownError(
          'Xiaohongshu Publication did not return a success confirmation.',
        )
      }

      let after

      try {
        after = normalizeRows(await options.runOpenCli(CREATOR_NOTES_ARGS))
      } catch (error) {
        throw createUnknownOutcomeError('Xiaohongshu', error)
      }
      const candidates = [...new Map(after
        .filter(note => note?.title === payload.title && note.id && note.url)
        .filter(note => !existingIds.has(String(note.id)))
        .map(note => [String(note.id), note])).values()]

      if (candidates.length !== 1) {
        throw new PublicationOutcomeUnknownError(
          `Published Xiaohongshu note could not be identified: ${payload.title}`,
        )
      }

      return {
        externalId: String(candidates[0].id),
        url: candidates[0].url,
      }
    },
  }
}

function deriveTitle(text) {
  const firstLine = text.split(/\r?\n/).find(line => line.trim())?.trim()

  if (!firstLine) {
    throw new Error('Xiaohongshu Publication requires text.')
  }

  return [...firstLine].slice(0, 20).join('')
}

function normalizeRows(value) {
  if (Array.isArray(value)) {
    return value
  }

  return value ? [value] : []
}
