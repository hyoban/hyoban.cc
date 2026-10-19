# @hyoban/api

A Cloudflare Worker that serves the Moments, places and trips from D1, for the site's
build and the recording app. Records come back exactly as stored, in the shape
`@hyoban/data-spec` defines. The recording app writes through it; until it does,
`pnpm data:import` copies `data/` in.

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

## Writing

Every write needs a token, runs as one transaction that bumps the revision, and stamps
the manifest's `updatedAt`. The app saves in this order:

| Path                                         | Does                                                                                                              |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `PUT /v1/places/:id`                         | adds a place; one within 200 m of a known place, or with its name, comes back as `{ id, place }` of the known one |
| `PUT /v1/moments/:uid/media/:mediaUid/:part` | keeps an original, `image` or `video` (a live photo sends both), until its Moment publishes                       |
| `PUT /v1/moments/:uid`                       | saves the Moment; the places it names must be known                                                               |
| `POST /v1/moments/:uid/publish`              | gives a draft its `YYYY/MM/DD-NN-slug` id and moves its originals to `moments/<id>/<slug>.<ext>`                  |
| `DELETE /v1/moments/:uid`                    | marks it deleted                                                                                                  |
| `PUT /v1/trips/:id`                          | saves a trip; the places it names must be known                                                                   |

A Moment's `published` block and each media item's are the server's: a request's copy
is ignored, they are set when the Moment publishes and never change after. Saving a
published Moment with new media publishes those as it saves, so their originals must
be uploaded first. The rules for places, ids and file names live in `src/publish.ts`,
which `pnpm data:publish` shares.

A write the site shows (anything but a draft) calls the `DEPLOY_HOOK_URL` secret, when
it is set, so the site builds again.

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
- `src/index.ts` routes requests; `src/database.ts` holds the reads and `src/writes.ts`
  the writes, against the few D1 and R2 calls they need (`Database`, `Bucket`), which
  the tests fill in with an in-memory `node:sqlite` and a map.
- `src/rows.ts` is how a record becomes a row, shared by the writes and the import.
- `src/import.ts` builds the SQL `pnpm data:import` runs: new and changed records are
  written, missing ones marked deleted, and unchanged rows keep their revision, so a
  client syncing afterwards only gets the difference.
