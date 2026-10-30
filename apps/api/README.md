# @hyoban/api

A Cloudflare Worker that serves the Moments, places and trips from D1 and their media from
R2, for the site's build and the Android app, which writes through it one change set at a
time. Records come back as stored, in the shapes `@hyoban/data-spec` defines. Deployment,
secrets and bindings are in [docs/deploy.md](../../docs/deploy.md).

## Reading

Without a token everything but drafts, private records and the export is public. A request
with `Authorization: Bearer <token>` must carry one of the `API_TOKENS` (comma-separated),
and then sees everything; any other token is a 401.

| Path                                          | Returns                                                                                  |
| --------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `GET /`                                       | `{ name, revision }`                                                                     |
| `GET /v2/changes?since=&limit=`               | the change feed: `entries`, `deleted`, `places`, `collections`, `tracks` and theirs gone |
| `GET /v2/entries/:uid`                        | one entry                                                                                |
| `GET /v2/collections`, `/v2/collections/:key` | `{ revision, collections }` by start date, or one by its slug (else its uid)             |
| `GET /v2/tracks`                              | `{ revision, tracks }`, the spans of footprints made public                              |
| `GET /v2/export`                              | `{ revision, manifest, places, entries, collections, tracks, routes }`; needs a token    |
| `GET /v1/changes?since=&limit=`               | the same feed in the first shape: `moments`, `places`, `trips` and theirs gone           |
| `GET /v1/moments/:uid`, `/v1/trips[/:id]`     | Moments and trips in the first shape                                                     |
| `GET /v1/places[/:id]`                        | `{ revision, places }` by id, or one place                                               |
| `GET /v1/routes`                              | `{ revision, trips }`, each published trip's route as stretches, with an `ETag`          |
| `GET /v1/export`                              | the export in the first shape; needs a token                                             |

### Two shapes

The records are entries, collections and public tracks (`@hyoban/data-spec/entry`), served
by `/v2`. `/v1` keeps serving Moments and trips while an app that reads them is installed.
Each row holds both (`migrations/0006_entries.sql`): `record` as first written, `entry` or
`collection`, and `shown`, what the public sees. An entry with no words (a bill alone, a
mark) has no Moment and is left out of `/v1`; a trip's waypoints come from its collection's
marks. Without a token `/v2` trims each entry as `getPublicEntry` does: no private entry or
part, a bill by its kind, amount, currency, category and note alone, and an entry made
private comes back in `deleted`.

### The change feed

Every write bumps the database's revision, and each row keeps the revision it last changed
at. `GET /v2/changes?since=<revision>` lists what changed after it, oldest first, `limit` at a
time (100 by default, at most 500), with the places and collections on the first page.
Follow `next` with `?cursor=<next>` until it is null, then keep `revision` for the next
`since`. Every page is of the revision the first was read at, so an entry never names a
place the feed hasn't handed out. `since=0` is everything; nothing is dropped from the
database, only marked deleted.

## Writing

Writes need a token.

| Path                                                      | Does                                                                                                        |
| --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `PUT /v2/entries/:uid/media/:mediaUid/:part`              | keeps an original, `image` or `video`, until its entry publishes                                            |
| `HEAD` on the same path                                   | 200 with the original's MD5 as its `ETag` once uploaded, else 404                                           |
| `POST /v2/changes`                                        | applies `{ places, entries, deleted, collections, deletedCollections, tracks, deletedTracks }` as one write |
| `PUT`/`HEAD /v1/moments/:uid/media/…`, `POST /v1/changes` | the same in the first shape (`{ places, moments, deleted }`)                                                |
| `POST /v1/build`                                          | calls the site's deploy hook and posts to the channel; `{ started }`                                        |
| `POST /v1/routes`                                         | fetches missing routes; `{ fetched, removed, failed, left }`                                                |

A change set is checked whole, then written in one transaction that bumps the revision and
stamps the manifest's `updatedAt`; one that doesn't fit changes nothing (a 422 names every
missing original), and one that changes nothing takes no revision. The answer gives the id
each place is known by, the records as published, the uids deleted and the revision taken.

- **Places.** One within 200 m of a known place, or of its name, is that place; any other
  is added, a provisional `p-<ulid>` getting an id from its name's pinyin, and its province,
  city and district looked up from DataV's GeoAtlas (`DIVISIONS_URL`), or left for
  `vp run places:locate` when that fails. An entry names one place at most.
- **Publishing.** A draft takes a `published` block with the time, and its originals move to
  `moments/<uid>/<media uid>.<ext>` (`getMediaKey`). The `published` blocks are the server's
  and never change. Media are kept as uploaded (`original: true`), and the site has
  Cloudflare resize them ([docs/assets.md](../../docs/assets.md)). The rules live in
  `@hyoban/data-spec/publish`.
- **Privacy.** A private part of a plugin that keeps to the phone (a bill) is dropped even
  when sent; a Moment written through `/v1` keeps the parts it knows nothing of.
- **After a write the site shows**, the trips' routes are brought up to date when what they
  are built from changed, then `DEPLOY_HOOK_URL` is called so the site builds again.

Records written outside the API go in with `vp run data:push` ([docs/data.md](../../docs/data.md)).

## Routes

Each leg between consecutive places of a trip, as `@hyoban/trip-map`'s `buildTrip` works
them out, has a route in the `routes` table by its key: a drive from Mapbox Directions
(`MAPBOX_TOKEN`), or for a train leg (`train:` key) a rail line from OpenRailRouting's demo
between the places' `station`s. Ferries and flights are straight. After a write the trips
see, the Worker fetches the legs it lacks (up to 30, 6 at once) and marks deleted those no
trip has. A leg with no way along it is remembered and drawn straight; a failed fetch is
retried after the next write.

`GET /v1/routes` gives each published trip's route as the site's overview draws it, drives
joined into one stretch and other legs dashed. The answer is cached in `meta` with its
revision, and its `ETag` covers the routes alone, so `If-None-Match` gets a 304 when a write
changed nothing drawn:

```json
{ "revision": 12, "trips": { "2026-yunnan": [{ "line": [[100.2, 26.8], …], "straight": false }] } }
```

## The Telegram channel

With `TELEGRAM_BOT_TOKEN`, the Worker posts the Moments the site shows to the channel
`TELEGRAM_CHAT_ID` names, through a bot that is an admin allowed to post and delete: the
words exactly, 📍 and the place, and a link to `https://hyoban.cc/m/<uid>/`, as the caption
of its photos and videos in albums of ten, or after them past 1024 characters. Photos go as
JPEGs from Cloudflare Images; a video over 50 MB goes as its first frame.

Changed words edit the post, changed media repost it, and a Moment the site stops showing
has its post deleted. `telegram_posts` keeps each Moment's messages. Only Moments published
since `telegram_since` are posted, so a restore never reposts old ones. It runs after a
write the site shows, after `POST /v1/build`, and on the 15-minute cron, from the revision
the last whole run reached (`telegram_revision`), under a lease in `meta` so two runs never
post twice.

## Setting up

```sh
vp run data:import            # migrations and data/ into the local database
vp run --filter @hyoban/api dev
```

Put `API_TOKENS=<token>` in `apps/api/.dev.vars` to try the token-only reads locally.

## Layout

- `migrations/`: the D1 schema. Each table keeps the record as written in a `record`
  column, so fields the spec doesn't know yet survive, plus the columns it is looked up by.
- `src/index.ts` routes requests; `src/database.ts` holds the reads and `src/writes.ts`
  the writes, against the few D1 and R2 calls they need (`Database`, `Bucket`), which the
  tests fill in with an in-memory `node:sqlite` and a map.
- `src/telegram.ts` posts to the channel, against a `Telegram` the tests fake.
- `src/rows.ts` turns a record into a row, for the writes and the import.
- `src/import.ts` builds the SQL `vp run data:import` runs: new and changed records
  written, missing ones marked deleted, unchanged rows keeping their revision.
