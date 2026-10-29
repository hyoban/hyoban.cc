# @hyoban/api

A Cloudflare Worker that serves the Moments, places and trips from D1, for the site's
build and the recording app. Records come back exactly as stored, in the shape
`@hyoban/data-spec` defines. The recording app writes through it, one change set at a
time; how it is deployed is in [docs/deploy.md](../../docs/deploy.md).

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

### The second shape, `/v2`

The records are entries, collections and public tracks (`@hyoban/data-spec/entry`), and
`/v2` serves them; `/v1` keeps serving Moments and trips, as the app installed before reads
them. Each row holds both shapes (`migrations/0006_entries.sql`): a Moment's `record` as
written, and `entry`; a trip's, and `collection`; `shown`, what of it the public sees. An entry
with no words, a bill alone or a mark (a place passed, a trip's waypoint), has no Moment: `/v1`
leaves it out, and a trip's waypoints are worked out from its collection's marks. Rows from
before an import in the second shape are read in it all the same, worked out from `record`.

| Path                                          | Returns or does                                                                           |
| --------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `GET /v2/changes?since=&limit=`               | as `/v1/changes`: `entries`, `deleted`, `places`, `collections`, `tracks` and theirs gone |
| `GET /v2/entries/:uid`                        | one entry                                                                                 |
| `GET /v2/collections`, `/v2/collections/:key` | `{ revision, collections }` by start date, or one by its slug (else its uid, lowercase)   |
| `GET /v2/tracks`                              | `{ revision, tracks }`: the spans of footprints made public                               |
| `GET /v2/export`                              | `{ revision, manifest, places, entries, collections, tracks, routes }`; needs a token     |
| `POST /v2/changes`                            | `{ places, entries, deleted, collections, deletedCollections, tracks, deletedTracks }`    |
| `PUT`/`HEAD /v2/entries/:uid/media/…`         | as a Moment's                                                                             |

Without a token the reads see only what is public, as `getPublicEntry` trims it: no private
entry or part, a bill by its kind, amount, currency, category and note alone, and an entry made
private comes back in `deleted`. A write in the second shape is checked and committed as one,
as `/v1`'s; a private part of a plugin that keeps to the phone (a bill) is dropped even when
sent, and a Moment written through `/v1` keeps the parts it knows nothing of. A write that
changes a collection or a mark works the trips the first shape reads out again.

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
| `POST /v1/build`                             | calls the site's deploy hook and posts to the channel; `{ started }`, for `data:push`       |

A change set names the places only the app knows, by id, the Moments it wrote or changed,
and the uids of those it deleted. A known place sent under its own id takes the kind it is
sent with, and keeps the rest as it is. A place within 200 m of a known one, or with its name,
is that one; any other is added, and one the app made, under the provisional id
`p-<ulid>`, gets an id spelled from its name's pinyin. A place added without a division
has the province, city and district its point is in looked up from DataV's GeoAtlas
(`DIVISIONS_URL`) and kept on it; when that fails it is kept without, for
`pnpm places:locate` to fill in. The Moments name each place by the id it is known by, one place at most: a Moment naming
two is turned down. Each Moment is saved and published: a draft takes a `published`
block with the time, and its originals move to `moments/<uid>/<media uid>.<ext>`
(`getMediaKey`); a published one keeps its blocks and publishes any new media. Nothing
names a Moment but its uid, so a published one can move to another day.
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

## The Telegram channel

With the `TELEGRAM_BOT_TOKEN` secret, the Worker posts the Moments the site shows to the
channel `TELEGRAM_CHAT_ID` names, `@hyoban_travel`, through a bot that is an admin of it
allowed to post and delete. A Moment goes as its words exactly, then 📍 and its place,
then a link to its page (`https://hyoban.cc/m/<uid>/`), as the caption of its photos and
videos in albums of ten, or as a message after them when the words are longer than a
caption's 1024 characters. Photos go as JPEGs Cloudflare Images makes from the kept
originals, a live photo as its still; a video is uploaded from the bucket, or as its
first frame when it is over the 50 MB a bot may send.

Changed words edit the post; changed media delete it and post the Moment again; a
Moment the site stops showing, hidden, a tech one or deleted, has its post deleted. A
message Telegram won't delete, as one deleted by hand, is logged and left. The
`telegram_posts` table keeps each Moment's post and messages. Only Moments published
since the channel was first synced (`telegram_since` in `meta`, when the migration ran)
are posted, so a restore never posts the old ones, and nothing posted before then is
touched.

It runs after a write the site shows, after `POST /v1/build`, and every 15 minutes on
the Worker's cron trigger, each looking only at the Moments changed since the revision
the last whole run reached (`telegram_revision`). A run holds a lease in `meta`, so two
at once never post a Moment twice; one that fails stops and the next starts again from
the same revision, leaving alone what is posted as it is. A Moment is posted as soon as
it publishes, so its link works once the site has built.

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

The site's build token is `MOMENTS_API_TOKEN` in the root `.env`. To write records
from `data/` into the deployed database use `pnpm data:push`; `pnpm data:import
--remote` restores the whole backup over it. Its deployment, secrets and bindings are
in [docs/deploy.md](../../docs/deploy.md).

## Layout

- `migrations/` is the D1 schema. Each table keeps the record as written in a
  `record` column, so fields the spec doesn't know yet survive, plus the columns it is
  looked up by.
- `src/index.ts` routes requests; `src/database.ts` holds the reads and `src/writes.ts`
  the writes, against the few D1 and R2 calls they need (`Database`, `Bucket`), which
  the tests fill in with an in-memory `node:sqlite` and a map.
- `src/telegram.ts` posts the Moments to the channel, against a `Telegram` the tests
  fill in with a bot that keeps its calls.
- `src/rows.ts` is how a record becomes a row, shared by the writes and the import.
- `src/import.ts` builds the SQL `pnpm data:import` runs: new and changed records are
  written, missing ones marked deleted, and unchanged rows keep their revision, so a
  client syncing afterwards only gets the difference.
