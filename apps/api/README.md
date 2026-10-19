# @hyoban/api

A Cloudflare Worker that serves the Moments, places and trips from D1, for the site's
build and the recording app. Records come back exactly as stored, in the shape
`@hyoban/data-spec` defines. For now it only reads; `data/` is still the source, and
`pnpm data:import` copies it in.

## Reading

| Path                            | Returns                                                                          |
| ------------------------------- | -------------------------------------------------------------------------------- |
| `GET /`                         | `{ name, revision }`                                                             |
| `GET /v1/moments?since=&limit=` | the change feed, see below                                                       |
| `GET /v1/moments/:uid`          | one Moment                                                                       |
| `GET /v1/places`                | `{ revision, places }`, places by id like `data/places.json`                     |
| `GET /v1/places/:id`            | one place                                                                        |
| `GET /v1/trips`                 | `{ revision, trips }`, by start date                                             |
| `GET /v1/trips/:id`             | one trip                                                                         |
| `GET /v1/export`                | `{ revision, manifest, places, trips, moments }`, drafts included; needs a token |

Without a token everything but drafts and the export is public. A request with an
`Authorization: Bearer <token>` header must carry one of the tokens in the
`API_TOKENS` secret, comma-separated, and then sees drafts too; any other token is a 401.

### The change feed

Every write bumps the database's revision, and each row keeps the revision it last
changed at. `GET /v1/moments?since=<revision>` lists the Moments changed after that
revision, oldest change first, `limit` at a time (100 by default, at most 500):

```json
{ "revision": 12, "moments": [], "deleted": ["01K…"], "next": "11.01K…" }
```

Follow `next` with `?cursor=<next>` until it is null, then keep `revision` and pass it
as `since` the next time. `since=0`, or none, is everything. Removed Moments come back
as uids in `deleted`; nothing is ever dropped from the database, only marked deleted.

## Setting up

```sh
pnpm data:import            # migrations and data/ into the local database
pnpm --filter @hyoban/api dev
```

Put `API_TOKENS=<token>` in `apps/api/.dev.vars` to try the token-only reads locally.

It is deployed at https://hyoban-cc-api.hyoban.workers.dev, over the `hyoban-cc`
database in APAC. Change the deployed data with `pnpm data:import --remote`, the code
with `pnpm --filter @hyoban/api deploy`, and the tokens with
`wrangler secret put API_TOKENS --config apps/api/wrangler.jsonc`. The site's build
token is `MOMENTS_API_TOKEN` in the root `.env`.

## Layout

- `migrations/` is the D1 schema. Each table keeps the record as written in a
  `record` column, so fields the spec doesn't know yet survive, plus the columns it is
  looked up by.
- `src/index.ts` routes requests; `src/database.ts` holds the queries against the few
  D1 calls it needs, which the tests fill in with an in-memory `node:sqlite`.
- `src/import.ts` builds the SQL `pnpm data:import` runs: new and changed records are
  written, missing ones marked deleted, and unchanged rows keep their revision, so a
  client syncing afterwards only gets the difference.
