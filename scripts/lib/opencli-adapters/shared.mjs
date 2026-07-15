import { PublicationOutcomeUnknownError } from '../publication.mjs'

export const OPENCLI_OPTIONS = ['--trace', 'retain-on-failure', '-f', 'json']

const SAFE_PRE_WRITE_ERROR_CODES = new Set([
  'ADAPTER_LOAD',
  'ARGUMENT',
  'AUTH_REQUIRED',
  'BROWSER_CONNECT',
  'CONFIG',
  'LOGIN_WALL',
])

export function formatPublicationText(moment) {
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

export function normalizeResult(value) {
  return Array.isArray(value) ? value[0] : value
}

export function normalizePublishedResult(result, platform) {
  if (result?.status !== 'success' || !result.id || !result.url) {
    throw new PublicationOutcomeUnknownError(
      `${platform} Publication completed without a verifiable external identity.`,
    )
  }

  return { externalId: String(result.id), url: result.url }
}

export async function runOpenCliWrite(options, args, platform) {
  try {
    return await options.runOpenCli(args)
  } catch (error) {
    if (SAFE_PRE_WRITE_ERROR_CODES.has(error?.code)) {
      throw error
    }

    throw createUnknownOutcomeError(platform, error)
  }
}

export function createUnknownOutcomeError(platform, error) {
  if (error instanceof PublicationOutcomeUnknownError) {
    return error
  }

  const message = error instanceof Error ? error.message : String(error)
  return new PublicationOutcomeUnknownError(
    `${platform} OpenCLI write outcome is unknown: ${message}`,
  )
}
