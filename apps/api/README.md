# @hyoban/api

A Cloudflare Worker that serves the Moments, places and trips from D1, for the site's
build and the recording app. Records come back exactly as stored, in the shape
`@hyoban/data-spec` defines. The recording app writes through it; until it does,
`pnpm data:import` copies `data/` in.

## Reading

| Path                            | Returns                                                                                  |
| ------------------------------- | ---------------------------------------------------------------------------------------- |
| `GET /`                         | `{ name, revision }`                                                                     |
| `GET /v1/moments?since=&limit=` | the change feed, see below                                                               |
| `GET /v1/moments/:uid`          | one Moment                                                                               |
| `GET /v1/places`                | `{ revision, places }`, places by id like `data/places.json`                             |
| `GET /v1/places/:id`            | one place                                                                                |
| `GET /v1/trips`                 | `{ revision, trips }`, by start date                                                     |
| `GET /v1/trips/:id`             | one trip                                                                                 |
| `GET /v1/routes`                | `{ revision, trips }`, each published trip's route as stretches, see below               |
| `GET /v1/export`                | `{ revision, manifest, places, trips, moments, routes }`, drafts included; needs a token |

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

The Android app instead uploads the originals, then sends everything it kept on the phone
as one `POST /v1/changes`: `{ places, moments, deleted }`, the places only it knows by
id, the Moments it wrote or changed, and the uids of those it deleted. Each place goes as
`PUT /v1/places/:id` takes one and the Moments name it by the id it is known by; each
Moment is saved and published, a draft taking the next order on its day after the
others in the same write. All of it is checked first, the originals among it, then
written in one transaction, so a request that doesn't fit changes nothing. The answer is
`{ places, moments, deleted }`: the id each place sent is known by, the Moments as
published, and the uids deleted, leaving out those already gone.

A place the app made carries the provisional id `p-<ulid>`; the API adds it under an id
spelled from its name's pinyin, as `pnpm data:publish` does, and the response's `id` is
the one the Moment then names.

Published photos and videos are kept as uploaded, marked `original: true`: the site has
Cloudflare Images resize the photos and takes each video's first frame as its poster
(docs/assets.md). A Moment's `published` block and each
media item's are the server's: a request's copy
is ignored, they are set when the Moment publishes and never change after. Saving a
published Moment with new media publishes those as it saves, so their originals must
be uploaded first. The rules for places, ids and file names live in `@hyoban/data-spec/publish`,
which `pnpm data:publish` shares.

A write the site shows (anything but a draft) first brings the trips' routes up to date,
then calls the `DEPLOY_HOOK_URL` secret, when it is set, so the site builds again with
them.

## Routes

Each leg between two consecutive places of a trip, as `@hyoban/trip-map`'s `buildTrip`
works them out from the trips and the published Moments, has a route kept in the
`routes` table by its key: a drive from Mapbox's Directions, with the `MAPBOX_TOKEN`
secret, or for a train leg (`train:` before the key) a rail line from
OpenRailRouting's public demo between the places' `station`s. Ferries and flights are
drawn straight. After a write the site shows, the Worker fetches the legs it doesn't
have, up to 30 at a time, and marks deleted the ones no trip has any more, so a moved
place or a changed trip is fetched again; `POST /v1/routes`, with a token, does the
same on demand and answers `{ fetched, removed, failed, left }` (`pnpm data:push`
calls it, as it writes past the Worker). `GET /v1/routes` gives each published trip's
route as the site's overview draws it, drives one after another as one stretch and
each train, plane or ferry as a dashed one:

```json
{ "revision": 12, "trips": { "2026-yunnan": [{ "line": [[100.2, 26.8], …], "straight": false }] } }
```

The export carries the routes by key, which the site's build and the backup read like
the other records (`data/routes.json`).

## Setting up

```sh
pnpm data:import            # migrations and data/ into the local database
pnpm --filter @hyoban/api dev
```

Put `API_TOKENS=<token>` in `apps/api/.dev.vars` to try the token-only reads locally.

It is deployed at https://hyoban-cc-api.hyoban.workers.dev, over the `hyoban-cc`
database in APAC. Workers Builds deploys it on every push to `main` that touches
`apps/api/`, `packages/data-spec/`, `packages/trip-map/` or `pnpm-lock.yaml`: its root directory is
`apps/api`, it installs from the repository root and runs `pnpm run deploy`, which
applies any new D1 migration before it deploys the code that needs it. The build's API
token has D1 Edit for that; a migration that fails stops the deploy.

The secrets are `API_TOKENS`, `DEPLOY_HOOK_URL` and `MAPBOX_TOKEN`, set with
`wrangler secret put <name> --config apps/api/wrangler.jsonc`. The site's build token
is `MOMENTS_API_TOKEN` in the root `.env`. To write records from `data/` into the
deployed database use `pnpm data:push`; `pnpm data:import --remote` restores the whole
backup over it.

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
