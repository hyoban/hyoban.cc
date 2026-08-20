import { execFile as execFileCallback } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'

import {
  ListObjectsV2Command,
  S3Client,
} from '@aws-sdk/client-s3'

const execFile = promisify(execFileCallback)
const REQUEST_TIMEOUT_MS = 30_000

export async function listR2Objects(options) {
  if (process.env.R2_ACCESS_KEY_ID || process.env.R2_SECRET_ACCESS_KEY) {
    return listR2ObjectsWithS3(options)
  }

  const token = await getCloudflareToken(options.root)
  const objects = []
  let cursor

  do {
    const url = new URL(
      `/client/v4/accounts/${options.accountId}/r2/buckets/${options.bucket}/objects`,
      'https://api.cloudflare.com',
    )
    url.searchParams.set('prefix', options.prefix ?? '')
    url.searchParams.set('per_page', '1000')

    if (cursor) {
      url.searchParams.set('cursor', cursor)
    }

    const result = await cloudflareRequest(url, token)
    objects.push(...result.result)
    cursor = result.result_info?.is_truncated
      ? result.result_info.cursor
      : undefined
  } while (cursor)

  return objects
}

async function listR2ObjectsWithS3(options) {
  if (!process.env.R2_ACCESS_KEY_ID || !process.env.R2_SECRET_ACCESS_KEY) {
    throw new Error(
      'R2_ACCESS_KEY_ID and R2_SECRET_ACCESS_KEY must both be set.',
    )
  }

  const client = new S3Client({
    credentials: {
      accessKeyId: process.env.R2_ACCESS_KEY_ID,
      secretAccessKey: process.env.R2_SECRET_ACCESS_KEY,
    },
    endpoint: `https://${options.accountId}.r2.cloudflarestorage.com`,
    region: 'auto',
  })
  const objects = []
  let continuationToken

  do {
    const result = await withRetries(
      () => client.send(new ListObjectsV2Command({
        Bucket: options.bucket,
        ContinuationToken: continuationToken,
        MaxKeys: 1000,
        Prefix: options.prefix ?? '',
      }), {
        abortSignal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      }),
      'list R2 objects through the S3 API',
    )

    objects.push(...(result.Contents ?? []).map(object => ({
      etag: object.ETag?.replaceAll('"', '') ?? null,
      key: object.Key,
      last_modified: object.LastModified?.toISOString() ?? null,
      size: object.Size ?? 0,
      storage_class: object.StorageClass ?? null,
    })))
    continuationToken = result.IsTruncated
      ? result.NextContinuationToken
      : undefined
  } while (continuationToken)

  return objects
}

async function getCloudflareToken(root) {
  if (process.env.CLOUDFLARE_API_TOKEN) {
    return process.env.CLOUDFLARE_API_TOKEN
  }

  const wrangler = join(root, 'node_modules', '.bin', 'wrangler')
  await execFile(wrangler, ['whoami'], {
    cwd: root,
    maxBuffer: 10 * 1024 * 1024,
  })

  const candidates = process.platform === 'darwin'
    ? [
        join(homedir(), 'Library/Preferences/.wrangler/config/default.toml'),
        join(homedir(), '.config/.wrangler/config/default.toml'),
      ]
    : [
        join(homedir(), '.config/.wrangler/config/default.toml'),
        join(homedir(), '.wrangler/config/default.toml'),
      ]

  for (const candidate of candidates) {
    try {
      const contents = await readFile(candidate, 'utf8')
      const token = contents.match(/^oauth_token\s*=\s*"([^"]+)"/m)?.[1]

      if (token) {
        return token
      }
    } catch (error) {
      if (error.code !== 'ENOENT') {
        throw error
      }
    }
  }

  throw new Error(
    'Unable to read Wrangler OAuth credentials. Set CLOUDFLARE_API_TOKEN.',
  )
}

async function cloudflareRequest(url, token) {
  return withRetries(async () => {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${token}`,
        'User-Agent': 'hyoban-moments',
      },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
    const result = await response.json()

    if (!response.ok || !result.success) {
      throw new Error(
        `Cloudflare API request failed: ${response.status} ${JSON.stringify(result.errors ?? [])}`,
      )
    }

    return result
  }, 'list R2 objects through the Cloudflare API')
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

  throw new Error(`Failed to ${label} after 5 attempts.`, { cause: lastError })
}
