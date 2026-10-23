# @hyoban/api

A Cloudflare Worker that serves the Moments, places and trips from D1, for the site's
build and the recording app. Records come back exactly as stored, in the shape
`@hyoban/data-spec` defines. The recording app writes through it, one change set at a
time; how it is deployed is in the root README.

## Reading

| Path                            | Returns                                                                                  |
| ------------------------------- | ---------------------------------------------------------------------------------------- |
| `GET /`                         | `{ name, revision }`                                                                     |
| `GET /v1/changes?since=&limit=` | the change feed, see below                                                               |
| `GET /v1/moments/:uid`          | one Moment                                                                               |
| `GET /v1/places`                | `{ revision, places }`, places by id like `data/places.json`                             |
| `GET /v1/places/:id`            | one place                                                                                |
| `GET /v1/trips`                 | `{ revision, trips }`, by start date                                                     |
| `GET /v1/trips/:id`             | one trip                                                                                 |
| `GET /v1/routes`                | `{ revision, trips }`, each published trip's route as stretches, with an `ETag`, below   |
| `GET /v1/export`                | `{ revision, manifest, places, trips, moments, routes }`, drafts included; needs a token |

Without a token everything but drafts and the export is public. A request with an
`Authorization: Bearer <token>` header must carry one of the tokens in the
`API_TOKENS` secret, comma-separated, and then sees drafts too; any other token is a 401.

### The change feed

Every write bumps the database's revision, and each row keeps the revision it last
changed at. `GET /v1/changes?since=<revision>` lists what changed after that revision:
the Moments oldest change first, `limit` at a time (100 by default, at most 500), and on
the first page the places and trips that changed:

```json
{
  "revision": 12,
  "moments": [],
  "deleted": ["01K…"],
  "places": { "nanjing": {} },
  "deletedPlaces": [],
  "trips": [],
  "deletedTrips": [],
  "next": "12.11.01K…"
}
```

Follow `next` with `?cursor=<next>` until it is null, then keep `revision` and pass it
as `since` the next time. Every page is of the revision the first was read at, so a
Moment never names a place the feed hasn't handed out yet. `since=0`, or none, is
everything there is. What was removed after a revision comes back by key in `deleted`,
`deletedPlaces` and `deletedTrips`, empty on a feed from the start, which has nothing
to remove; nothing is ever dropped from the database, only marked deleted.

The reads put their answers together from the records as stored, without parsing them.

## Writing

The app writes as a commit: what it changed waits on the phone until it is looked over,
then goes all at once. Writes need a token.

| Path                                         | Does                                                                                        |
| -------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `PUT /v1/moments/:uid/media/:mediaUid/:part` | keeps an original, `image` or `video` (a live photo sends both), until its Moment publishes |
| `HEAD` on the same path                      | 200 with the original's MD5 as its `ETag` when it is uploaded, else 404                     |
| `POST /v1/changes`                           | applies `{ places, moments, deleted }` as one write                                         |

A change set names the places only the app knows, by id, the Moments it wrote or changed,
and the uids of those it deleted. A place within 200 m of a known one, or with its name,
is that one; any other is added, and one the app made, under the provisional id
`p-<ulid>`, gets an id spelled from its name's pinyin. A place added without a division
has the province, city and district its point is in looked up from DataV's GeoAtlas
(`DIVISIONS_URL`) and kept on it; when that fails it is kept without, for
`pnpm places:locate` to fill in. The Moments name each place by the id it is known by, one place at most: a Moment naming
two is turned down. Each Moment is saved and published: a draft takes its
`YYYY/MM/DD-NN-slug` id, the next order on its day after the others in the same write,
slugged by its first place or else its first words, and its originals move to
`moments/<id>/<slug>.<ext>`; a published one keeps its id and publishes any new media.
A deleted Moment is marked deleted for the change feed. All of it is checked first, then
written in one transaction that bumps the revision and stamps the manifest's
`updatedAt`, so a change set that doesn't fit changes nothing, and the originals it had
copied are removed again, as when one of them isn't uploaded: the 422 names every one
missing. A Moment sent exactly as stored isn't written, and a change set that changes
nothing takes no revision.
The answer is `{ places, moments, deleted, revision }`: the id each place sent is known
by, the Moments as published, the uids deleted, leaving out those already gone, and the
revision the write took, left out when it took none. A client whose copy is of the
revision just before it has all the write did, and can keep that revision.

Published photos and videos are kept as uploaded, marked `original: true`: the site has
Cloudflare Images resize the photos and takes each video's first frame as its poster
(docs/assets.md). A Moment's `published` block and each media item's are the server's:
a request's copy is ignored, they are set when the Moment publishes and never change
after. The rules for places, ids and file names live in `@hyoban/data-spec/publish`.

Records written outside the API, by the create-moment skill, go into the database with
`pnpm data:push` instead (docs/data.md).

A write the site shows first brings the trips' routes up to date, when it changed what
the trips are built from (a Moment's day, places or order, or whether it is shown),
then calls the `DEPLOY_HOOK_URL` secret, when it is set, so the site builds again with
them.

## Routes

Each leg between two consecutive places of a trip, as `@hyoban/trip-map`'s `buildTrip`
works them out from the trips and the published Moments, has a route kept in the
`routes` table by its key: a drive from Mapbox's Directions, with the `MAPBOX_TOKEN`
secret, or for a train leg (`train:` before the key) a rail line from
OpenRailRouting's public demo between the places' `station`s. Ferries and flights are
drawn straight. After a write the trips see, the Worker fetches the legs it doesn't
have, up to 30 a write and 6 at once, and marks deleted the ones no trip has any more, so a moved
place or a changed trip is fetched again. A leg the router finds no way along, as a
drive across the sea, is remembered in `meta` and drawn straight without being asked
for again; one whose fetch failed is asked for after the next write; `POST /v1/routes`, with a token, does the
same on demand and answers `{ fetched, removed, failed, left }` (`pnpm data:push`
calls it, as it writes past the Worker). `GET /v1/routes` gives each published trip's
route as the site's overview draws it, drives one after another as one stretch and
each train, plane or ferry as a dashed one. Working it out reads every Moment, so the
answer is kept in `meta` with the revision it is of, worked out after a write the trips
see from the same read that fills the routes, carried on to the new revision by a write
they don't, and worked out again on a request once the revision has moved on otherwise,
as after `data:push`. Its `ETag` is of the routes alone, so a client sending it back
as `If-None-Match` gets a 304 when a write changed nothing it draws:

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
