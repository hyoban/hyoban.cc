import { authorize } from './auth.ts'
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

// The read side of the API. Everything is public except drafts, which only the owner
// sees, and the full export. Records come back exactly as stored, in the shape
// @hyoban/data-spec defines.

export interface Env {
  DB: Database
  API_TOKENS?: string
}

const DEFAULT_LIMIT = 100
const MAX_LIMIT = 500
const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/
const ID = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export default {
  fetch: handle,
}

export async function handle(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'GET') {
    return error(405, 'Only GET is supported.', { Allow: 'GET' })
  }

  const access = await authorize(request, env.API_TOKENS)

  if (access === 'denied') {
    return error(401, 'Unknown token.', { 'WWW-Authenticate': 'Bearer' })
  }

  const url = new URL(request.url)
  const [version, collection, key, ...rest] = url.pathname.split('/').filter(Boolean)
  const owner = access === 'owner'
  const db = env.DB

  if (version === undefined) {
    return json({ name: 'hyoban.cc', revision: await readRevision(db) })
  }

  if (version !== 'v1' || rest.length > 0) {
    return notFound()
  }

  switch (collection) {
    case 'moments': {
      if (key === undefined) {
        const query = readFeedQuery(url.searchParams)

        return 'error' in query
          ? error(400, query.error)
          : json(await readMomentChanges(db, { ...query, drafts: owner }))
      }

      return ULID.test(key) ? found(await readMoment(db, key, { drafts: owner })) : notFound()
    }
    case 'places': {
      if (key === undefined) {
        return json({ revision: await readRevision(db), places: await readPlaces(db) })
      }

      return ID.test(key) ? found(await readPlace(db, key)) : notFound()
    }
    case 'trips': {
      if (key === undefined) {
        return json({ revision: await readRevision(db), trips: await readTrips(db) })
      }

      return ID.test(key) ? found(await readTrip(db, key)) : notFound()
    }
    case 'export': {
      if (key !== undefined) {
        return notFound()
      }

      return owner
        ? json(await readSnapshot(db))
        : error(401, 'The export needs a token.', { 'WWW-Authenticate': 'Bearer' })
    }
    default:
      return notFound()
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

function found(record: unknown) {
  return record ? json(record) : notFound()
}

function notFound() {
  return error(404, 'Not found.')
}

function error(status: number, message: string, headers: Record<string, string> = {}) {
  return json({ error: message }, status, headers)
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return Response.json(body, { status, headers })
}
