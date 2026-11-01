import { createHash } from 'node:crypto'
import { readdirSync, readFileSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import { fileURLToPath } from 'node:url'

import { entryToMoment, isMomentEntry, momentToEntry } from '@hyoban/data-spec/entry'
import {
  listJsonFiles,
  readJson,
  readMomentRecords,
  readTripRecords,
} from '../../web/src/data/store.ts'
import { buildImportSql } from '../src/import.ts'
import { handle } from '../src/index.ts'

// The Worker over an in-memory SQLite shaped like D1 and a map shaped like R2, with the
// migrations applied, for the tests.

const migrations = new URL('../migrations/', import.meta.url)

export const token = 'test-token'

/** A made-up data/, in data/'s layout, for the tests that need records. */
export const fixtureRoot = fileURLToPath(
  new URL('../../../packages/data-spec/test/fixtures/data/', import.meta.url),
)

/** The fixture as it is kept: entries, collections and the places beside them. */
export const entrySnapshot = {
  manifest: readJson('manifest.json', fixtureRoot),
  places: readJson('places.json', fixtureRoot),
  entries: listJsonFiles('entries', fixtureRoot).map((file) => readJson(file, fixtureRoot)),
  collections: listJsonFiles('collections', fixtureRoot).map((file) => readJson(file, fixtureRoot)),
  tracks: [],
  routes: readJson('routes.json', fixtureRoot),
}

/** The fixture as Moments and trips, what the tests that write Moments take it as; the import takes either. */
export const snapshot = {
  manifest: readJson('manifest.json', fixtureRoot),
  places: readJson('places.json', fixtureRoot),
  trips: readTripRecords(fixtureRoot),
  moments: readMomentRecords(fixtureRoot).map(({ record }) => record),
  routes: readJson('routes.json', fixtureRoot),
}

export function setUp() {
  const sqlite = new DatabaseSync(':memory:')

  for (const file of readdirSync(migrations).sort()) {
    sqlite.exec(readFileSync(new URL(file, migrations), 'utf8'))
  }

  const statement = (query, values = []) => ({
    query,
    values,
    bind: (...next) => statement(query, next),
    all: async () => ({ results: sqlite.prepare(query).all(...values) }),
    first: async () => sqlite.prepare(query).get(...values) ?? null,
    run: async () => sqlite.prepare(query).run(...values),
  })
  const db = {
    prepare: (query) => statement(query),
    batch: async (statements) => {
      sqlite.exec('BEGIN')

      try {
        // As D1: a SELECT's result carries its rows.
        const results = statements.map(({ query, values }) =>
          /^\s*SELECT/i.test(query)
            ? { results: sqlite.prepare(query).all(...values) }
            : sqlite.prepare(query).run(...values),
        )
        sqlite.exec('COMMIT')
        return results
      } catch (error) {
        sqlite.exec('ROLLBACK')
        throw error
      }
    },
  }
  const objects = new Map()
  const describe = (key) => {
    const object = objects.get(key)
    return (
      object && {
        key,
        size: object.bytes.byteLength,
        etag: object.etag,
        httpMetadata: object.httpMetadata,
      }
    )
  }
  const bucket = {
    objects,
    async put(key, value, options = {}) {
      const bytes = new Uint8Array(await new Response(value).arrayBuffer())
      const etag = createHash('md5').update(bytes).digest('hex')

      objects.set(key, { bytes, etag, httpMetadata: options.httpMetadata ?? {} })

      return describe(key)
    },
    async get(key) {
      const object = objects.get(key)
      return object ? { ...describe(key), body: new Response(object.bytes).body } : null
    },
    async head(key) {
      return describe(key) ?? null
    },
    async delete(keys) {
      // As R2: a delete names from 1 to 1000 keys.
      if ([keys].flat().length === 0) {
        throw new Error(
          'delete: The number of keys in the request must be between 1 and 1000 inclusive. (10027)',
        )
      }

      for (const key of [keys].flat()) {
        objects.delete(key)
      }
    },
  }
  const env = { DB: db, BUCKET: bucket, API_TOKENS: `other, ${token}` }
  const importSnapshot = (value = snapshot, now = '2026-10-19T12:00:00Z') =>
    sqlite.exec(buildImportSql(value, now))

  return { bucket, env, importSnapshot, sqlite }
}

/** A request to the Worker, with the token unless `auth` names another or is null, and its JSON reply. */
export async function call(env, method, path, { auth = token, body, headers = {}, context } = {}) {
  const json = body !== undefined && !(body instanceof Uint8Array)
  const response = await handle(
    new Request(`https://api.test${path}`, {
      method,
      headers: {
        ...(auth ? { Authorization: `Bearer ${auth}` } : {}),
        ...(json ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      ...(body === undefined ? {} : { body: json ? JSON.stringify(body) : body }),
    }),
    env,
    context,
  )
  const text = await response.text()

  return { status: response.status, body: text ? JSON.parse(text) : null }
}

export function get(env, path, options) {
  return call(env, 'GET', path, options)
}

/** The tables `GET /v2/rows` hands out, by what each is called in a change. */
const KINDS = {
  entries: ['entries', 'deleted'],
  places: ['places', 'deletedPlaces'],
  collections: ['collections', 'deletedCollections'],
  tracks: ['tracks', 'deletedTracks'],
  vehicle_months: ['vehicleMonths', 'deletedVehicleMonths'],
  vehicle_assisted: ['vehicleAssisted', 'deletedVehicleAssisted'],
  vehicle_captures: ['vehicleCaptures', 'deletedVehicleCaptures'],
}

/**
 * What changed after `since`, every page of `GET /v2/rows` read: the records still there by
 * kind, places by id, and the keys of those deleted, as `{ revision, entries, deleted, places,
 * deletedPlaces, collections, … }`.
 */
export async function readChanges(env, since = 0, { limit = 500, auth } = {}) {
  const changes = Object.fromEntries(
    Object.entries(KINDS).flatMap(([table, [live, gone]]) => [
      [live, table === 'places' ? {} : []],
      [gone, []],
    ]),
  )
  let path = `/v2/rows?since=${since}&limit=${limit}`

  while (path) {
    const { status, body } = await call(env, 'GET', path, auth ? { auth } : {})

    if (status !== 200) {
      throw new Error(`${path}: ${status}`)
    }

    changes.revision ??= body.revision

    for (const row of body.rows) {
      const [live, gone] = KINDS[row.table] ?? []

      if (!live) {
        continue
      }

      if (row.deletedAt) {
        changes[gone].push(row.key)
      } else if (row.table === 'places') {
        changes.places[row.key] = row.record
      } else {
        changes[live].push(row.record)
      }
    }

    path = body.next && `/v2/rows?cursor=${body.next}&limit=${limit}`
  }

  return changes
}

/**
 * `POST /v2/changes` with Moments, as the app sends them, each as its entry; its reply, with
 * the Moments it published worked out from their entries.
 */
export async function publishMoments(env, { moments = [], ...changes }, options = {}) {
  const reply = await call(env, 'POST', '/v2/changes', {
    ...options,
    body: { ...changes, entries: [...(changes.entries ?? []), ...moments.map(momentToEntry)] },
  })

  return Array.isArray(reply.body?.entries)
    ? { ...reply, body: { ...reply.body, moments: momentsOf(reply.body.entries) } }
    : reply
}

/** A Moment as `GET /v2/entries/:uid` has it. */
export async function getMoment(env, uid, options) {
  const reply = await get(env, `/v2/entries/${uid}`, options)

  return reply.status === 200 ? { ...reply, body: entryToMoment(reply.body) } : reply
}

/** The entries with words, as Moments. */
export function momentsOf(entries) {
  return entries.filter(isMomentEntry).map((entry) => entryToMoment(entry))
}
