const noteIdPattern = /^[0-9a-f]{24}$/i

export function parseXiaohongshuProfileNotes(value) {
  if (!Array.isArray(value)) {
    throw new TypeError('The Xiaohongshu profile response must be an array.')
  }

  const ids = new Set()

  return value.map((item) => {
    if (!item || typeof item !== 'object' || !noteIdPattern.test(item.id)) {
      throw new TypeError('The Xiaohongshu profile contains an invalid note ID.')
    }

    if (ids.has(item.id)) {
      throw new Error(`Duplicate Xiaohongshu note ID: ${item.id}`)
    }

    if (item.type !== 'normal' && item.type !== 'video') {
      throw new Error(`Unsupported Xiaohongshu note type for ${item.id}: ${item.type}`)
    }

    if (typeof item.url !== 'string'
      || extractXiaohongshuNoteId(item.url) !== item.id
      || !new URL(item.url).searchParams.get('xsec_token')) {
      throw new Error(`Missing signed public URL for Xiaohongshu note ${item.id}.`)
    }

    ids.add(item.id)
    return {
      id: item.id.toLowerCase(),
      signedUrl: item.url,
      title: normalizeText(item.title),
      type: item.type,
    }
  })
}

export function parseXiaohongshuNoteFields(value) {
  if (!Array.isArray(value)) {
    throw new TypeError('The Xiaohongshu note response must be an array.')
  }

  const fields = new Map()

  for (const row of value) {
    if (!row || typeof row !== 'object'
      || typeof row.field !== 'string'
      || typeof row.value !== 'string') {
      throw new TypeError('The Xiaohongshu note response contains an invalid field.')
    }

    fields.set(row.field, normalizeText(row.value))
  }

  return {
    author: fields.get('author') ?? '',
    content: fields.get('content') ?? '',
    title: fields.get('title') ?? '',
  }
}

export function publishedAtFromXiaohongshuNoteId(id) {
  if (!noteIdPattern.test(id)) {
    throw new Error(`Unsupported Xiaohongshu note ID: ${id}`)
  }

  const seconds = Number.parseInt(id.slice(0, 8), 16)
  const date = new Date((seconds + 8 * 60 * 60) * 1000)

  if (Number.isNaN(date.valueOf())) {
    throw new Error(`Unsupported Xiaohongshu note ID: ${id}`)
  }

  return `${date.toISOString().slice(0, 19)}+08:00`
}

export function composeXiaohongshuText(title, content) {
  const normalizedTitle = normalizeTitle(title)
  const normalizedContent = normalizeText(content)

  if (!normalizedTitle) {
    return normalizedContent
  }

  if (!normalizedContent) {
    return normalizedTitle
  }

  if (normalizedContent === normalizedTitle
    || normalizedContent.split('\n', 1)[0].trim() === normalizedTitle) {
    return normalizedContent
  }

  return `${normalizedTitle}\n\n${normalizedContent}`
}

export function canonicalXiaohongshuSourceUrl(id) {
  if (!noteIdPattern.test(id)) {
    throw new Error(`Unsupported Xiaohongshu note ID: ${id}`)
  }

  return `https://www.xiaohongshu.com/explore/${id.toLowerCase()}`
}

export function extractXiaohongshuNoteId(value) {
  if (typeof value !== 'string') {
    return undefined
  }

  try {
    const url = new URL(value)

    if (url.hostname !== 'xiaohongshu.com' && !url.hostname.endsWith('.xiaohongshu.com')) {
      return undefined
    }

    const queryId = url.searchParams.get('noteId')

    if (queryId && noteIdPattern.test(queryId)) {
      return queryId.toLowerCase()
    }

    return url.pathname
      .split('/')
      .reverse()
      .find(segment => noteIdPattern.test(segment))
      ?.toLowerCase()
  } catch {
    return undefined
  }
}

export function detectXiaohongshuMediaType(buffer) {
  if (!Buffer.isBuffer(buffer)) {
    throw new TypeError('Xiaohongshu media detection requires a Buffer.')
  }

  if (buffer.length >= 12
    && buffer.subarray(0, 4).toString('ascii') === 'RIFF'
    && buffer.subarray(8, 12).toString('ascii') === 'WEBP') {
    return { extension: '.webp', type: 'image' }
  }

  if (buffer.length >= 3
    && buffer[0] === 0xff
    && buffer[1] === 0xd8
    && buffer[2] === 0xff) {
    return { extension: '.jpg', type: 'image' }
  }

  if (buffer.length >= 8
    && buffer.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'))) {
    return { extension: '.png', type: 'image' }
  }

  if (buffer.length >= 6 && /^GIF8[79]a$/.test(buffer.subarray(0, 6).toString('ascii'))) {
    return { extension: '.gif', type: 'image' }
  }

  if (buffer.length >= 12 && buffer.subarray(4, 8).toString('ascii') === 'ftyp') {
    return { extension: '.mp4', type: 'video' }
  }

  throw new Error('Unsupported Xiaohongshu media file.')
}

export function planXiaohongshuMedia(noteType, downloadedMedia) {
  if (!Array.isArray(downloadedMedia) || downloadedMedia.length === 0) {
    throw new Error('A Xiaohongshu note must contain downloaded media.')
  }

  const sorted = [...downloadedMedia].sort((first, second) => first.index - second.index)

  if (new Set(sorted.map(item => item.index)).size !== sorted.length) {
    throw new Error('Xiaohongshu media contains duplicate download indexes.')
  }

  if (noteType === 'normal') {
    if (sorted.some(item => item.type !== 'image')) {
      throw new Error('A Xiaohongshu image note contains unsupported video media.')
    }

    return sorted.map(item => ({
      extension: item.extension,
      sourcePath: item.sourcePath,
      type: 'image',
    }))
  }

  if (noteType === 'video') {
    const videos = sorted.filter(item => item.type === 'video')
    const images = sorted.filter(item => item.type === 'image')

    if (videos.length !== 1 || images.length > 1) {
      throw new Error(
        `Expected one video and at most one cover image, found ${videos.length} video(s) and ${images.length} image(s).`,
      )
    }

    const [video] = videos
    const [poster] = images

    return [{
      extension: video.extension,
      ...(poster
        ? { posterExtension: poster.extension, posterPath: poster.sourcePath }
        : {}),
      sourcePath: video.sourcePath,
      type: 'video',
    }]
  }

  throw new Error(`Unsupported Xiaohongshu note type: ${noteType}`)
}

export function validateXiaohongshuDownload(output, downloadedMedia) {
  const report = parseDownloadReport(output)

  if (!Array.isArray(downloadedMedia)) {
    throw new TypeError('Downloaded Xiaohongshu media must be an array.')
  }

  const reportIndexes = new Set()

  for (const item of report) {
    if (!item || typeof item !== 'object'
      || !Number.isSafeInteger(item.index)
      || item.index < 1
      || (item.type !== 'image' && item.type !== 'video')
      || typeof item.status !== 'string') {
      throw new Error('OpenCLI returned a malformed Xiaohongshu download report.')
    }

    if (reportIndexes.has(item.index)) {
      throw new Error(`OpenCLI reported duplicate media index ${item.index}.`)
    }

    if (item.status !== 'success') {
      throw new Error(`Xiaohongshu media download failed at index ${item.index}.`)
    }

    reportIndexes.add(item.index)
  }

  if (report.length !== downloadedMedia.length) {
    throw new Error(
      `OpenCLI reported ${report.length} media file(s), found ${downloadedMedia.length} on disk.`,
    )
  }

  const filesByIndex = new Map(downloadedMedia.map(item => [item.index, item]))

  if (filesByIndex.size !== downloadedMedia.length) {
    throw new Error('Downloaded Xiaohongshu media contains duplicate indexes.')
  }

  for (const item of report) {
    const file = filesByIndex.get(item.index)

    if (!file || file.type !== item.type) {
      throw new Error(`Xiaohongshu media report does not match disk at index ${item.index}.`)
    }
  }

  return report
}

function parseDownloadReport(output) {
  if (typeof output !== 'string') {
    throw new TypeError('The Xiaohongshu download output must be a string.')
  }

  const trimmed = output.trim()
  const reportText = trimmed.match(/(?:^|[\r\n])(\[\s*\{[\s\S]*\]\s*)$/)?.[1] ?? trimmed
  let report

  try {
    report = JSON.parse(reportText)
  } catch (error) {
    throw new Error('OpenCLI returned an invalid Xiaohongshu download report.', {
      cause: error,
    })
  }

  if (!Array.isArray(report) || report.length === 0) {
    throw new Error('OpenCLI returned an empty Xiaohongshu download report.')
  }

  return report
}

function normalizeTitle(value) {
  const title = normalizeText(value)
  return title === '(无标题)' || title === '（无标题）' ? '' : title
}

function normalizeText(value) {
  return typeof value === 'string'
    ? value.replaceAll('\u00a0', ' ').replace(/\r\n?/g, '\n').trim()
    : ''
}
