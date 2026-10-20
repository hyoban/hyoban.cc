import { createHash } from 'node:crypto'
import { readdirSync, readFileSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'

import { listJsonFiles, readJson } from '../../../src/data/store.ts'
import { buildImportSql } from '../src/import.ts'
import { handle } from '../src/index.ts'

// The Worker over an in-memory SQLite shaped like D1 and a map shaped like R2, with the
// migrations applied, for the tests.

const migrations = new URL('../migrations/', import.meta.url)

export const token = 'test-token'

/** The repository's data/, as the import takes it. */
export const snapshot = {
  manifest: readJson('manifest.json'),
  places: readJson('places.json'),
  trips: listJsonFiles('trips').map((file) => readJson(file)),
  moments: listJsonFiles('moments').map((file) => readJson(file)),
  routes: readJson('routes.json'),
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

/** A request to the Worker, with the token when `auth` names one, and its JSON reply. */
export async function call(env, method, path, { auth, body, headers = {}, context } = {}) {
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
