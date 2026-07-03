import { existsSync, readFileSync } from 'node:fs'
import { readFile, writeFile } from 'node:fs/promises'

const TMDB_API_BASE_URL = 'https://api.themoviedb.org/3'
const LANGUAGE = 'zh-CN'

const rootUrl = new URL('../', import.meta.url)
const envUrl = new URL('.env', rootUrl)
const recommendationsUrl = new URL('src/data/media-recommendations.json', rootUrl)
const generatedUrl = new URL('src/data/media.generated.json', rootUrl)
const force = process.argv.includes('--force')

loadDotEnv(envUrl)

const recommendations = JSON.parse(await readFile(recommendationsUrl, 'utf8'))

if (!Array.isArray(recommendations)) {
  throw new TypeError('src/data/media-recommendations.json must contain an array.')
}

const cachedItemsByKey = force
  ? new Map()
  : await readCachedItemsByKey()
const pendingItemsByKey = new Map()
const stats = {
  fetched: 0,
  reused: 0,
}
const sortedRecommendations = [...recommendations].sort(compareMedia)
const items = await Promise.all(sortedRecommendations.map(resolveRecommendation))

await writeFile(
  generatedUrl,
  `${JSON.stringify({
    generatedAt: new Date().toISOString(),
    language: LANGUAGE,
    provider: 'TMDB',
    items,
  }, null, 2)}\n`,
)

console.log(
  `Generated ${items.length} media item(s) at src/data/media.generated.json (${stats.reused} reused, ${stats.fetched} fetched${force ? ', forced' : ''}).`,
)

async function resolveRecommendation(recommendation) {
  validateRecommendation(recommendation)
  const key = getMediaKey(recommendation)
  const cachedItem = cachedItemsByKey.get(key)

  if (cachedItem) {
    stats.reused += 1
    return {
      type: recommendation.type,
      tmdbId: recommendation.tmdbId,
      data: cachedItem.data,
    }
  }

  if (!pendingItemsByKey.has(key)) {
    pendingItemsByKey.set(key, fetchRecommendation(recommendation))
  }

  return pendingItemsByKey.get(key)
}

async function fetchRecommendation(recommendation) {
  stats.fetched += 1

  return {
    type: recommendation.type,
    tmdbId: recommendation.tmdbId,
    data: await fetchTmdbDetails(recommendation.type, recommendation.tmdbId),
  }
}

async function readCachedItemsByKey() {
  if (!existsSync(generatedUrl)) {
    return new Map()
  }

  const generated = JSON.parse(await readFile(generatedUrl, 'utf8'))

  if (generated.provider !== 'TMDB' || generated.language !== LANGUAGE || !Array.isArray(generated.items)) {
    return new Map()
  }

  return new Map(
    generated.items
      .filter(isGeneratedItem)
      .map(item => [getMediaKey(item), item]),
  )
}

function isGeneratedItem(item) {
  return item
    && typeof item === 'object'
    && (item.type === 'movie' || item.type === 'tv')
    && Number.isInteger(item.tmdbId)
    && item.data
    && typeof item.data === 'object'
}

function getMediaKey(media) {
  return `${media.type}:${media.tmdbId}`
}

function compareMedia(first, second) {
  return first.tmdbId - second.tmdbId || first.type.localeCompare(second.type)
}

function validateRecommendation(recommendation) {
  if (!recommendation || typeof recommendation !== 'object') {
    throw new TypeError('Each recommendation must be an object.')
  }

  if (recommendation.type !== 'movie' && recommendation.type !== 'tv') {
    throw new TypeError(`Invalid media type "${recommendation.type}". Use "movie" or "tv".`)
  }

  if (!Number.isInteger(recommendation.tmdbId)) {
    throw new TypeError(`Invalid TMDB id "${recommendation.tmdbId}". Use an integer.`)
  }
}

async function fetchTmdbDetails(type, tmdbId) {
  return fetchTmdb(`/${type}/${tmdbId}`, {
    append_to_response: 'credits',
    language: LANGUAGE,
  })
}

async function fetchTmdb(path, params) {
  const token = process.env.TMDB_API_TOKEN
  const apiKey = process.env.TMDB_API_KEY

  if (!token && !apiKey) {
    throw new Error('Missing TMDB credentials. Set TMDB_API_TOKEN or TMDB_API_KEY before running this script.')
  }

  const url = new URL(`${TMDB_API_BASE_URL}${path}`)

  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value)
  }

  if (apiKey) {
    url.searchParams.set('api_key', apiKey)
  }

  const response = await fetch(url, {
    headers: token
      ? {
          Authorization: `Bearer ${token}`,
        }
      : undefined,
  })

  if (!response.ok) {
    const body = await response.text()
    throw new Error(`TMDB request failed: ${response.status} ${response.statusText} ${body.slice(0, 200)}`)
  }

  return response.json()
}

function loadDotEnv(fileUrl) {
  if (!existsSync(fileUrl)) {
    return
  }

  const text = readFileSync(fileUrl, 'utf8')

  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim()

    if (!trimmed || trimmed.startsWith('#')) {
      continue
    }

    const separatorIndex = trimmed.indexOf('=')

    if (separatorIndex === -1) {
      continue
    }

    const key = trimmed.slice(0, separatorIndex).trim()
    const rawValue = trimmed.slice(separatorIndex + 1).trim()

    if (!key || process.env[key] !== undefined) {
      continue
    }

    process.env[key] = rawValue.replace(/^['"]|['"]$/g, '')
  }
}
