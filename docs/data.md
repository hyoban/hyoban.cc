# Data

Moments, places and trips live in the API's D1 database (`apps/api`). The Android app writes
it, the site's build reads it, and every request needs a token. Git keeps none of them.

`data/` is a local copy that `vp run data:export` writes for the scripts, as JSON in the
shape `packages/data-spec` defines. Git ignores it, so edit records through the API, not
here. Two parts of `data/` are in git:

- `data/zeekr/`, the car's statistics ([its README](../data/zeekr/README.md)). The
  statistics are in the API too; the car's expenses and the redacted sources live only in
  the repository.
- `data/schema/`.

Tests read a made-up copy in the same layout, `packages/data-spec/test/fixtures/data/`.

```
data/
├─ manifest.json                 schemaVersion, generator, updatedAt
├─ entries/YYYY/MM/<ulid>.json   an entry
├─ collections/<slug>.json       a collection, such as a trip
├─ places.json                   places by id
├─ routes.json                   each trip leg's route by its key, as the API fetched it
├─ zeekr/                        the car's statistics, expenses and sources
└─ schema/*.schema.json          generated from the spec by `vp run data:schema`
```

## Records

### Entries

An **entry** (`packages/data-spec/src/entry.ts`) is a moment and an optional point. Its
words, photos, cost, bill, charge, device, subscription, check-in or occasion are parts, one
per plugin.

- An entry is named by its uid alone. Its page is `/m/<uid>/` and its media sit under
  `moments/<uid>/` in R2, so its day, place and words can change after publishing.
- An entry without `published` is a draft the site skips. `published` blocks (the entry's
  and each media item's) never change.
- **Cost**: what a Moment cost by itself. It is public with the Moment, however private its
  bill. It has a kind (`food` for a meal, or another), what it went on (a meal's dishes), a
  verdict (好吃, 一般 or 踩雷 for a meal, else 值, 一般 or 不值), the amount when known, its
  currency and how many people it was for.
  - Older meals are a `food` part (dishes, verdict, cost, people), read as a cost until
    `vp run data:costs` moves them. The first shape reads a meal as its `food`, what it cost
    as its `bill`, and any other cost as `cost`.
- **Device**: on the day it was bought; once sold, its sale price is written on it. A
  device's `id` names one device.
- **Subscription**: on the day it was last paid, or, for software bought once, the day it
  was first written down. The use page lists devices and subscriptions from these entries.
- **Charge**: its `vehicle` names one of the devices. The API rejects a change set where a
  device `id` names more than one device or a `vehicle` names none.
- **Check-in**: brought in from Swarm (its `source` holds that app's ids), or made on the
  phone from a footprints stay (`source.app` `phone`), spanning the stay's start to its
  `end`. It is public unless Swarm kept it private or the phone made it so. The map dots a
  public one at its place among the Moments there, by its venue alone.
- **Occasion**: a day that matters (a birthday, an anniversary, a deadline), on the entry
  of the day it started. With `repeat` it recurs yearly, by the Gregorian or lunar
  calendar, counted in 岁, 周年 or not at all. Without it, it is that one day. The calendar
  marks them.

### Trips

A **trip** is a collection. It takes the entries in its span (adjusted by `include` and
`exclude`), its waypoints (entries with a place and no parts) and its public check-ins with
a place, ordered by time.

- Its bills are the public bill entries in its span, by time alone. A bill names no trip.
  The trip's page lists them among its Moments with what they spent.
- Its slug (or uid) is its key and never changes. The site serves it at an address built
  from its year and pinyin title, such as `/trip/2026/xuzhou-zhoumo-xing/`, recomputed at
  each build, and redirects its earlier addresses.
- Either end of its span may be a time: what came before it on the first day, or after it
  on the last, is not in the trip. The app can tie an end to a record in `bounds` (`from`,
  `to`, each an entry's uid, a check-in or a Moment); that end then follows the record's
  time.

### Places

A **place** has a name, WGS-84 coordinates, a kind, its province, city and district
(`vp run places:locate`), and an optional note. A train's end has a `station`; one brought
in from Swarm keeps the venue it was made for (`source`). Places are never merged for being
near each other or sharing a name.

### Visibility, modules and units

- A record is public or private by its own `visibility`, else its container's, else its
  plugin's default (a bill is private), else public.
- Plugins are grouped into **modules** (`modules` in `entry.ts`: footprints, ledger, things,
  car, food, cost, check-ins, occasions). Each owns its plugins' parts and its own tables
  beyond entries. The app turns a module on or off as a whole; the data is the same either
  way.
- Units come from `packages/data-spec/src/primitives.ts`: metres, watt-hours, seconds, and
  yuan when no `currency` is given. Event times keep their original offset; `createdAt` and
  `updatedAt` are UTC. A charge still writes `durationMin` beside `durationSeconds` for
  older installed apps.

`vp run data:check` checks `data/` as a whole, beyond each record's schema: every entry and
collection parses, what they reference exists, and device ids hold. It warns about fields
the spec doesn't name, which the loose schemas let through.

## Building from the API

The site builds from the API with `MOMENTS_API_TOKEN` in the environment or `.env`.
`apps/web/src/data/api-data.ts` fetches `/v2/export` once, writes it under
`apps/web/.artifacts/api-data/` in this layout, and reads from there.

- A token that fails to fetch fails the build.
- No token fails in CI, and locally reads the made-up fixtures.
- `HYOBAN_DATA_ROOT` set to a directory in this layout builds from it instead, as the tests
  do with the fixtures.

The scripts read `data/`.

## Writing outside the API

Anything that writes records into `data/` itself, like the create-moment skill, must export
first and push afterwards. Otherwise the site never shows the change, and the next
`data:export` stops rather than overwrite it.

```sh
vp run data:export                                    # data/ from the API first
vp run data:push data/entries/2026/10/<uid>.json      # then the records written
```

`data:push` merges. It writes the given entries and collections, every place they name, any
`--place <id>` and any car statistics files given. It removes nothing, so what the app
published meanwhile stays. It writes the deployed database, then has the API fetch new legs'
routes and start a site build (`POST /v2/build`), since a write that bypasses the Worker
triggers none. `--local` writes the database `wrangler dev` uses; `--dry-run` only prints
the SQL.

## Exporting and restoring

```sh
vp run data:export            # the API's export into data/
vp run data:import            # data/ into the local database `wrangler dev` uses
vp run data:import --remote   # data/ into the deployed one, to restore it
```

`data:export` needs `MOMENTS_API_TOKEN`. It writes canonical JSON and removes what the API
no longer has, so a run with nothing new changes nothing. It stores the digest of every
record it wrote, and every record `data:push` sent, in `data/.synced.json`. It stops when a
record in `data/` differs from its digest, or when the car's statistics have uncommitted
changes, since those may not be in the API yet.

`data:import` makes the database mirror `data/`, so use it only to restore. It applies the
migrations, writes new and changed records and marks the rest deleted, including anything
the app published since the export. Only those changes take a new revision, so the app's
sync sees just the difference. `--dry-run` writes the SQL under `.artifacts/import/`. D1's
Time Travel also keeps 30 days of the database.
