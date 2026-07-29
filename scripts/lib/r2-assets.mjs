import { createHash } from 'node:crypto'
import { execFile as execFileCallback } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { extname, join } from 'node:path'
import { promisify } from 'node:util'

const execFile = promisify(execFileCallback)

const contentTypes = new Map([
  ['.avif', 'image/avif'],
  ['.gif', 'image/gif'],
  ['.jpeg', 'image/jpeg'],
  ['.jpg', 'image/jpeg'],
  ['.mov', 'video/quicktime'],
  ['.mp4', 'video/mp4'],
  ['.png', 'image/png'],
  ['.webm', 'video/webm'],
  ['.webp', 'image/webp'],
])

export function getContentType(file) {
  const contentType = contentTypes.get(extname(file).toLowerCase())

  if (!contentType) {
    throw new Error(`Unsupported asset type: ${file}`)
  }

  return contentType
}

export async function uploadR2Object(options) {
  const objectUrl = new URL(encodeObjectKey(options.key), `${options.origin}/`)
  const contents = await readFile(options.file)
  const expectedEtag = createHash('md5').update(contents).digest('hex')
  const remote = await inspectRemoteObject(objectUrl)

  if (remote.exists) {
    if (remote.etag === expectedEtag && remote.size === contents.byteLength) {
      return { action: 'reused', url: objectUrl.href }
    }

    throw new Error(
      `Refusing to overwrite ${objectUrl.href}: the existing object does not match ${options.file}`,
    )
  }

  const wrangler = join(options.root, 'node_modules', '.bin', 'wrangler')
  await withRetries(
    () => execFile(wrangler, [
      'r2',
      'object',
      'put',
      `${options.bucket}/${options.key}`,
      '--file',
      options.file,
      '--content-type',
      getContentType(options.file),
      '--cache-control',
      'public, max-age=31536000, immutable',
      '--remote',
    ], {
      cwd: options.root,
      maxBuffer: 10 * 1024 * 1024,
    }),
    `upload ${options.key}`,
  )

  const uploaded = await inspectRemoteObject(objectUrl)

  if (!uploaded.exists || uploaded.etag !== expectedEtag || uploaded.size !== contents.byteLength) {
    throw new Error(`R2 verification failed for ${objectUrl.href}`)
  }

  return { action: 'uploaded', url: objectUrl.href }
}

async function inspectRemoteObject(url) {
  const response = await withRetries(async () => {
    const result = await fetch(url, {
      cache: 'no-store',
      method: 'HEAD',
    })

    if (result.status >= 500) {
      throw new Error(`${result.status} ${result.statusText}`)
    }

    return result
  }, `inspect ${url.href}`)

  if (response.status === 404) {
    return { exists: false }
  }

  if (!response.ok) {
    throw new Error(`Unable to inspect ${url.href}: ${response.status} ${response.statusText}`)
  }

  return {
    etag: response.headers.get('etag')?.replaceAll('"', ''),
    exists: true,
    size: Number.parseInt(response.headers.get('content-length') ?? '', 10),
  }
}

async function withRetries(operation, label) {
  let lastError

  for (let attempt = 1; attempt <= 5; attempt += 1) {
    try {
      return await operation()
    } catch (error) {
      lastError = error

      if (attempt < 5) {
        await new Promise(resolve => setTimeout(resolve, 250 * 2 ** (attempt - 1)))
      }
    }
  }

  throw new Error(`Failed to ${label} after 5 attempts`, { cause: lastError })
}

function encodeObjectKey(key) {
  return key
    .split('/')
    .map(segment => encodeURIComponent(segment))
    .join('/')
}
