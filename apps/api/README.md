# @hyoban/api

A Cloudflare Worker that serves entries, collections, places, trip routes and the car's
statistics from D1, and their media from R2. The site's build reads it; the Android app
writes through it, one change set at a time. Records come back as stored, in the shapes
`@hyoban/data-spec` defines. Deployment, secrets and bindings are in
[docs/deploy.md](../../docs/deploy.md).

## Reading

Every request, `GET /` included, needs `Authorization: Bearer <token>` with one of the
comma-separated `API_TOKENS`; anything else is a 401. A valid token sees everything, drafts
and private records included. The site filters to what is public itself (`getPublicEntry`).

| Path                                          | Returns                                                                                                         |
| --------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `GET /`                                       | `{ name, revision, assets }`; `assets` is the origin photos and videos are served from                          |
| `GET /v2/rows?since=&limit=`                  | every row of every table, deleted ones too, for a row-for-row copy of D1                                        |
| `GET /v2/entries/:uid`                        | one entry                                                                                                       |
| `GET /v2/collections`, `/v2/collections/:key` | `{ revision, collections }` by start date, or one by its slug (else its uid)                                    |
| `GET /v2/export`                              | `{ revision, manifest, places, entries, collections, routes, vehicleMonths, vehicleAssisted, vehicleCaptures }` |
| `GET /v2/places[/:id]`                        | `{ revision, places }` by id, or one place                                                                      |
| `GET /v2/routes`                              | `{ revision, trips }`, each published trip's route as stretches, with an `ETag`                                 |

Records are entries and collections (`@hyoban/data-spec/entry`). Every table is one of
`@hyoban/data-spec/tables`: a key, the revision it last changed at, its
deletion time, the record's JSON, and columns SQLite derives from the record. `schema.sql` is
generated from them by `vp run data:tables`, and the migrations must end at it. The app's
copy uses the same tables.

### The rows

Every write bumps the database's revision; each row keeps the revision it last changed at.

- `GET /v2/rows?since=<revision>` returns rows changed after it, deleted ones too, `limit`
  at a time (default 100, max 500). Each is `{ table, key, rev, deletedAt, record }`.
- Covers every table in `@hyoban/data-spec/tables` except routes (served by
  `GET /v2/routes`), one table at a time in order, so places come before the entries that
  name them.
- Follow `next` with `?cursor=<next>` until it is null, then keep `revision` as the next
  `since`. All pages are read at the first page's revision; later changes wait for the next
  read.
- `database` (a random number in `meta`) identifies the database. A copy that holds another
  one must read every row again (`since=0`).
- Nothing is ever dropped, only marked deleted, so the app can mirror D1's tables.

## Writing

| Path                                         | Does                                                                                                                |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `PUT /v2/entries/:uid/media/:mediaUid/:part` | keeps an original, `image` or `video`, until its entry publishes; 422 for an image that says where it was taken     |
| `HEAD` on the same path                      | 200 with the original's MD5 as its `ETag` once uploaded, else 404                                                   |
| `POST /v2/changes`                           | applies `{ places, entries, deleted, collections, deletedCollections }` as one write; an imported charge is refused |
| `POST /v2/build`                             | calls the site's deploy hook and posts to the channel; `{ started }`                                                |
| `POST /v2/routes`                            | fetches missing routes; `{ fetched, removed, failed, left }`                                                        |

A change set is validated as a whole, then written in one transaction that bumps the
revision and stamps the manifest's `updatedAt`. An invalid one changes nothing (a 422 names
every missing original); one that changes nothing takes no revision. The response gives each
place's id, the records as published, the deleted uids and the new revision.

- **Places.** A place keeps the id it is sent with, the app's `p-<ulid>` included, so bills,
  check-ins and Moments never need moving. A known id updates that place's name, point and
  kind; any other id adds a place. Places are never merged, even when close, same-named or
  of one Swarm venue; picking a known place is the app's job.
- **Divisions.** A new or moved place gets its province, city and district from DataV's
  GeoAtlas (`DIVISIONS_URL`); if that fails, `vp run places:locate` fills them later. A moved
  place also re-routes its trips. An entry names at most one place.
- **Publishing.** A draft gets a `published` block with the time, and its originals move to
  `moments/<uid>/<media uid>.<ext>` (`getMediaKey`). `published` blocks belong to the server
  and never change. Media are kept as uploaded (`original: true`); the site has Cloudflare
  resize them ([docs/assets.md](../../docs/assets.md)). Accepted media types are
  `@hyoban/data-spec/publish`'s.
- **Location.** An original is public at its key, so it must not say where it was taken. An
  image with a GPS position in EXIF or XMP is refused (`@hyoban/data-spec/location`). Videos
  are streamed through unread; the app strips their location before upload.
- **Privacy.** For a plugin that stays on the phone (a bill), a private part is dropped even
  when sent, and a public one keeps only its public fields (`getUploadEntry`).
- **After a write the site shows**, trip routes are updated if their inputs changed, then
  `DEPLOY_HOOK_URL` is called to rebuild the site.

Records written outside the API go in with `vp run data:push` ([docs/data.md](../../docs/data.md)),
which then calls `POST /v2/build`: the site rebuilds and new Moments post to the channel.

## Routes

Each leg between consecutive places of a trip (its Moments, marks and public check-ins, as
`@hyoban/trip-map`'s `buildTrip` and the spec's `collectionToTrip` derive them) has a route in the
`routes` table:

- **Drives** come from FOSSGIS's public Valhalla. The spur driven out and back at either end
  to reach a place across a divided road is trimmed (trip-map's `trimTurnBacks`), with its
  distance and time.
- **Trains** (`train:` keys) come from OpenRailRouting's demo, between the places'
  `station`s.
- **Ferries and flights** are straight.

Neither router needs a key, so the app can route the same way. After a write the trips see,
the Worker fetches missing legs (up to 30, 2 at a time, as the public routers ask) and marks
deleted those no trip uses. A leg with no way along it is remembered and drawn straight; a
failed fetch is retried after the next write. `POST /v2/routes` fills on request;
`POST /v2/routes?again=drives` first queues every drive for re-routing (e.g. after changing
router). Each fill takes from that queue after the missing legs, and a leg keeps its old route
until the new one arrives.

`GET /v2/routes` gives each published trip's route as the site's overview draws it: drives
joined into one stretch, other legs dashed. The response is cached in `meta` with its
revision. Its `ETag` covers only the routes, so `If-None-Match` gets a 304 when a write
changed nothing drawn:

```json
{ "revision": 12, "trips": { "2026-yunnan": [{ "line": [[100.2, 26.8], …], "straight": false }] } }
```

## The Telegram channel

With `TELEGRAM_BOT_TOKEN` set, the Worker posts the Moments the site shows to the channel
`TELEGRAM_CHAT_ID`, through a bot that is an admin allowed to post and delete.

- **Caption**: the exact text; 📍 and the place; 🕐 and the time on its own clock (year only if
  not the current year); 🧳 and the trip unless it is private; 💰 人均 with the cost and verdict
  (😋 好吃, 😌 还行 or 😖 踩雷 for a meal, else 👍 值, 👌 还行 or 👎 不值). No link.
- **Media**: albums of ten, the caption on them, or sent after them past 1024 characters.
  Photos go as JPEGs from Cloudflare Images; a video over 50 MB goes as its first frame.
- **Updates**: changed text edits the post; changed media are replaced in their messages. A
  change in media count reposts it, since Telegram can't add to or remove from an album.
- **Deletes**: a Moment the site stops showing, or one made planned, has its post deleted. A
  planned Moment is posted only once it becomes real.
- `telegram_posts` keeps each Moment's messages. Only Moments published since
  `telegram_since` are posted, so a restore never reposts old ones.
- It runs after a write the site shows, after `POST /v2/build` and on the 15-minute cron,
  from the revision the last complete run reached (`telegram_revision`), under a lease in
  `meta` so two runs never post twice.

## Setting up

```sh
vp run data:import            # migrations and data/ into the local database
vp run --filter @hyoban/api dev
```

Put `API_TOKENS=<token>` in `apps/api/.dev.vars`: like the deployed API, the local one
answers nothing without it. `data:import` needs an exported `data/` (see
[docs/data.md](../../docs/data.md)).

## Layout

- `migrations/`: the D1 schema. Each table keeps the record as written in a `record` column,
  so fields the spec doesn't know yet survive, plus the columns it is looked up by.
- `src/index.ts` routes requests, `src/auth.ts` checks tokens, `src/http.ts` shapes
  responses.
- `src/database.ts` wraps the D1 calls (`Database`) and place reads; `src/bucket.ts` wraps
  R2 (`Bucket`). Tests back them with in-memory `node:sqlite` and a map.
- `src/entries.ts` reads entries and collections, writes `/v2/export` and applies change
  sets; `src/writes.ts` uploads and publishes media, names places and commits.
- `src/copy.ts` serves `/v2/rows`, `src/routes.ts` trip routes, `src/site.ts` the deploy
  hook.
- `src/telegram.ts` posts to the channel, against a `Telegram` the tests fake.
- `src/rows.ts` turns a record into a row, for writes and the import.
- `src/import.ts` builds the SQL `vp run data:import` runs: new and changed records written,
  missing ones marked deleted, unchanged rows keeping their revision.
