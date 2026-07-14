import { extname } from 'node:path'

const TELEGRAM_ORIGIN = 'https://telegram.me'
const IMAGE_EXTENSIONS = new Map([
  ['image/avif', '.avif'],
  ['image/gif', '.gif'],
  ['image/jpeg', '.jpg'],
  ['image/png', '.png'],
  ['image/webp', '.webp'],
])

export function parseTelegramExport(exportData, source) {
  const chats = exportData?.chats?.list

  if (!Array.isArray(chats)) {
    throw new Error('Telegram export does not contain a chat list.')
  }

  const chat = chats.find(candidate => String(candidate.id) === String(source.chatId))

  if (!chat) {
    throw new Error(
      `Telegram export does not contain ${source.channel} (chat ${source.chatId}).`,
    )
  }

  const channelAuthorId = `channel${chat.id}`
  const authoredMessages = []
  const ignoredIds = []
  let skippedForwarded = 0
  let skippedOtherAuthors = 0
  let skippedService = 0

  for (const message of chat.messages) {
    if (message.type !== 'message') {
      skippedService += 1
      ignoredIds.push(String(message.id))
      continue
    }

    if (message.forwarded_from) {
      skippedForwarded += 1
      ignoredIds.push(String(message.id))
      continue
    }

    if (message.from_id !== channelAuthorId) {
      skippedOtherAuthors += 1
      ignoredIds.push(String(message.id))
      continue
    }

    authoredMessages.push(message)
  }

  const groups = groupMessages(authoredMessages)
  const moments = groups
    .map(group => normalizeGroup(group, source.channel))
    .filter(Boolean)

  return {
    chatId: chat.id,
    chatName: chat.name,
    ignoredIds,
    moments,
    skippedForwarded,
    skippedOtherAuthors,
    skippedService,
  }
}

function groupMessages(messages) {
  const groups = []

  for (const message of [...messages].sort((first, second) => first.id - second.id)) {
    const group = groups.at(-1)

    if (group && belongsToAlbum(group, message)) {
      group.push(message)
    } else {
      groups.push([message])
    }
  }

  return groups
}

function belongsToAlbum(group, message) {
  const previous = group.at(-1)
  const timeDifference = Number(message.date_unixtime) - Number(previous.date_unixtime)
  const captions = group.filter(item => normalizeText(item.text)).length

  return group.length < 10
    && hasMediaMarker(previous)
    && hasMediaMarker(message)
    && message.id === previous.id + 1
    && timeDifference >= 0
    && timeDifference <= 1
    && (message.reply_to_message_id ?? null) === (previous.reply_to_message_id ?? null)
    && !(captions > 0 && normalizeText(message.text))
}

function normalizeGroup(group, channel) {
  const first = group[0]
  const media = group.map(getMediaDescriptor).filter(Boolean)
  const text = [...new Set(group.map(message => normalizeText(message.text)).filter(Boolean))]
    .join('\n\n')

  if (!text && media.length === 0) {
    return undefined
  }

  return {
    id: String(first.id),
    media,
    publishedAt: formatSingaporeDate(first.date_unixtime),
    sourceUrl: `${TELEGRAM_ORIGIN}/${channel}/${first.id}`,
    text,
  }
}

function getMediaDescriptor(message) {
  if (isAvailablePath(message.photo)) {
    return {
      extension: normalizeImageExtension(extname(message.photo)) ?? '.jpg',
      sourcePath: message.photo,
      type: 'image',
    }
  }

  if (!isAvailablePath(message.file)) {
    return undefined
  }

  if (message.media_type === 'video_file' || message.mime_type === 'video/mp4') {
    return {
      extension: '.mp4',
      ...(isAvailablePath(message.thumbnail) ? { posterPath: message.thumbnail } : {}),
      sourcePath: message.file,
      type: 'video',
    }
  }

  const imageExtension = IMAGE_EXTENSIONS.get(message.mime_type)

  return imageExtension
    ? { extension: imageExtension, sourcePath: message.file, type: 'image' }
    : undefined
}

function normalizeText(value) {
  const parts = Array.isArray(value) ? value : [value]

  return parts
    .map((part) => {
      if (typeof part === 'string') {
        return part
      }

      if (!part || typeof part.text !== 'string') {
        return ''
      }

      if (part.type === 'text_link' && part.href && !isVisibleUrl(part.text)) {
        return `${part.text} (${part.href})`
      }

      return part.text
    })
    .join('')
    .replaceAll('\u00a0', ' ')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function formatSingaporeDate(value) {
  const timestamp = Number(value)
  const date = new Date(timestamp * 1000)

  if (!Number.isFinite(timestamp) || Number.isNaN(date.valueOf())) {
    throw new Error(`Unsupported Telegram date: ${value}`)
  }

  const formatter = new Intl.DateTimeFormat('en-CA', {
    day: '2-digit',
    hour: '2-digit',
    hourCycle: 'h23',
    minute: '2-digit',
    month: '2-digit',
    second: '2-digit',
    timeZone: 'Asia/Singapore',
    year: 'numeric',
  })
  const parts = Object.fromEntries(
    formatter.formatToParts(date).map(part => [part.type, part.value]),
  )

  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}+08:00`
}

function hasMediaMarker(message) {
  return Boolean(message.photo || message.file || message.media_type)
}

function isAvailablePath(value) {
  return typeof value === 'string'
    && value.length > 0
    && !value.startsWith('(File unavailable')
}

function normalizeImageExtension(extension) {
  const normalized = extension.toLowerCase()

  return ['.avif', '.gif', '.jpeg', '.jpg', '.png', '.webp'].includes(normalized)
    ? normalized.replace('.jpeg', '.jpg')
    : undefined
}

function isVisibleUrl(value) {
  try {
    new URL(value)
    return true
  } catch {
    return false
  }
}
