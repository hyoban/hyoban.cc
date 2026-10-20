import { authorize } from './auth.ts'
import type { Bucket, UploadPart } from './bucket.ts'
import {
  parseCursor,
  readMoment,
  readMomentChanges,
  readPlace,
  readPlaces,
  readRevision,
  readSnapshot,
  readTrip,
  readTrips,
  type Database,
} from './database.ts'
import { error, HttpError, json, readBody } from './http.ts'
import { createRouteFetcher, fillRoutes, readTripRoutes } from './routes.ts'
import { applyChanges, uploadMedia, type WriteContext, type WriteResult } from './writes.ts'

// The API. Reads are public except drafts, which only the owner sees, and the full
// export; writes are the owner's. Records come back exactly as stored, in the shape
// @hyoban/data-spec defines. A write the site shows first brings the trips' routes up to
// date, when MAPBOX_TOKEN is set, then calls DEPLOY_HOOK_URL, when there is one, so the
// site builds again with them.

export interface Env {
  DB: Database
  BUCKET: Bucket
  API_TOKENS?: string
  DEPLOY_HOOK_URL?: string
  /** For the trips' driving routes, from Mapbox's Directions. */
  MAPBOX_TOKEN?: string
}

interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void
}

const DEFAULT_LIMIT = 100
const MAX_LIMIT = 500
const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/
const ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export default {
  fetch: handle,
}

export async function handle(
  request: Request,
  env: Env,
  executionContext?: ExecutionContext,
): Promise<Response> {
  try {
    return await route(request, env, executionContext)
  } catch (caught) {
    if (caught instanceof HttpError) {
      return error(caught.status, caught.message, caught.headers)
    }

    console.error(caught)

    return error(500, 'Something went wrong.')
  }
}

async function route(request: Request, env: Env, executionContext?: ExecutionContext) {
  const access = await authorize(request, env.API_TOKENS)

  if (access === 'denied') {
    return error(401, 'Unknown token.', { 'WWW-Authenticate': 'Bearer' })
  }

  const url = new URL(request.url)
  const segments = url.pathname.split('/').filter(Boolean)
  const [version, collection, key, ...rest] = segments
  const owner = access === 'owner'
  const method = request.method
  const db = env.DB

  if (version === undefined) {
    return method === 'GET'
      ? json({ name: 'hyoban.cc', revision: await readRevision(db) })
      : notAllowed('GET')
  }

  if (version !== 'v1') {
    return notFound()
  }

  if (method !== 'GET' && !owner) {
    throw new HttpError(401, 'Writing needs a token.', { 'WWW-Authenticate': 'Bearer' })
  }

  const write = async <T>(run: (context: WriteContext) => Promise<WriteResult<T>>) => {
    const result = await run({ db, bucket: env.BUCKET, now: timestamp() })

    if (result.changesSite) {
      executionContext?.waitUntil(afterSiteChange(env))
    }

    return result.status === 204
      ? new Response(null, { status: 204 })
      : json(result.body, result.status)
  }

  switch (collection) {
    case 'moments': {
      if (key === undefined) {
        if (method !== 'GET') {
          return notAllowed('GET')
        }

        const query = readFeedQuery(url.searchParams)

        return 'error' in query
          ? error(400, query.error)
          : json(await readMomentChanges(db, { ...query, drafts: owner }))
      }

      if (!ULID.test(key)) {
        return notFound()
      }

      if (rest.length === 0) {
        return method === 'GET'
          ? found(await readMoment(db, key, { drafts: owner }))
          : notAllowed('GET')
      }

      const [media, mediaUid, part, ...more] = rest

      if (
        media === 'media' &&
        mediaUid &&
        ULID.test(mediaUid) &&
        (part === 'image' || part === 'video') &&
        more.length === 0
      ) {
        return method === 'PUT'
          ? write((context) =>
              uploadMedia(context, request, {
                momentUid: key,
                mediaUid,
                part: part satisfies UploadPart,
              }),
            )
          : notAllowed('PUT')
      }

      return notFound()
    }
    case 'places': {
      if (rest.length > 0) {
        return notFound()
      }

      if (key === undefined) {
        return method === 'GET' ? json(await readPlaces(db)) : notAllowed('GET')
      }

      if (!ID.test(key)) {
        return notFound()
      }

      return method === 'GET' ? found(await readPlace(db, key)) : notAllowed('GET')
    }
    case 'trips': {
      if (rest.length > 0) {
        return notFound()
      }

      if (key === undefined) {
        return method === 'GET' ? json(await readTrips(db)) : notAllowed('GET')
      }

      if (!ID.test(key)) {
        return notFound()
      }

      return method === 'GET' ? found(await readTrip(db, key)) : notAllowed('GET')
    }
    case 'routes': {
      if (key !== undefined) {
        return notFound()
      }

      switch (method) {
        case 'GET':
          return json(await readTripRoutes(db))
        case 'POST': {
          if (!env.MAPBOX_TOKEN) {
            return error(503, 'MAPBOX_TOKEN is not set.')
          }

          const result = await fillRoutes(
            { db, bucket: env.BUCKET, now: timestamp() },
            createRouteFetcher(env.MAPBOX_TOKEN),
          )

          return json(result)
        }
        default:
          return notAllowed('GET, POST')
      }
    }
    case 'changes': {
      if (key !== undefined) {
        return notFound()
      }

      if (method !== 'POST') {
        return notAllowed('POST')
      }

      const body = await readBody(request)

      return write((context) => applyChanges(context, body))
    }
    case 'export': {
      if (key !== undefined) {
        return notFound()
      }

      if (method !== 'GET') {
        return notAllowed('GET')
      }

      return owner
        ? json(await readSnapshot(db))
        : error(401, 'The export needs a token.', { 'WWW-Authenticate': 'Bearer' })
    }
    default:
      return notFound()
  }
}

/**
 * After a write the site shows: the trips' routes first, so the build the deploy hook
 * starts has them, and what `GET /v1/routes` answers worked out ahead of the app's next
 * sync, then the hook. None of them failing undoes the write.
 */
async function afterSiteChange(env: Env) {
  if (env.MAPBOX_TOKEN) {
    try {
      const result = await fillRoutes(
        { db: env.DB, bucket: env.BUCKET, now: timestamp() },
        createRouteFetcher(env.MAPBOX_TOKEN),
      )

      if (result.fetched || result.removed || result.failed || result.left) {
        console.log('Routes', result)
      }
    } catch (caught) {
      console.error('Bringing the routes up to date failed', caught)
    }
  }

  try {
    await readTripRoutes(env.DB)
  } catch (caught) {
    console.error("Working out the trips' routes failed", caught)
  }

  if (env.DEPLOY_HOOK_URL) {
    try {
      const response = await fetch(env.DEPLOY_HOOK_URL, { method: 'POST' })

      if (!response.ok) {
        console.error('The deploy hook answered', response.status, await response.text())
      }
    } catch (caught) {
      console.error('The deploy hook failed', caught)
    }
  }
}

/** `?since=<revision>` or `?cursor=<next>`, and `?limit=`. */
function readFeedQuery(params: URLSearchParams) {
  const limit = Number(params.get('limit') ?? DEFAULT_LIMIT)

  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) {
    return { error: `limit is a whole number from 1 to ${MAX_LIMIT}.` }
  }

  const cursorParam = params.get('cursor')

  if (cursorParam !== null) {
    const cursor = parseCursor(cursorParam)

    return cursor ? { cursor, limit } : { error: 'cursor is the next value of a page.' }
  }

  const since = Number(params.get('since') ?? 0)

  if (!Number.isInteger(since) || since < 0) {
    return { error: 'since is a revision, a whole number.' }
  }

  // Everything after revision `since`: from the first uid of the revision after it.
  return { cursor: { rev: since + 1, uid: '' }, limit }
}

/** Now, to the second, as the records stamp times. */
function timestamp() {
  return new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
}

function found(record: unknown) {
  return record ? json(record) : notFound()
}

function notFound() {
  return error(404, 'Not found.')
}

function notAllowed(allow: string) {
  return error(405, `Use ${allow}.`, { Allow: allow })
}
