# Data

The Moments, places and trips live in the API's D1 database (`apps/api`), which the
Android app writes and the site's build reads; every request to it needs a token. Git
keeps none of them. `data/` is a local copy, as JSON records in the shape
`packages/data-spec` defines, that `vp run data:export` writes for the scripts and git
leaves out, so edit records through the API rather than here. The car's statistics are in
the API too, and in `data/zeekr/` (see [its README](../data/zeekr/README.md)), which git
keeps with the car's expenses and the redacted sources, which live only in the
repository. `data/schema/` is in git as well. The tests read a made-up copy in this layout,
`packages/data-spec/test/fixtures/data/`.

```
data/
├─ manifest.json                 schemaVersion, generator, updatedAt
├─ entries/YYYY/MM/<ulid>.json   an entry
├─ collections/<slug>.json       a collection, such as a trip
├─ tracks/<ulid>.json            a public span of the footprints
├─ places.json                   places by id
├─ routes.json                   each trip leg's route by its key, as the API fetched it
├─ zeekr/                        the car's statistics, expenses and sources
└─ schema/*.schema.json          generated from the spec by `vp run data:schema`
```

- An **entry** (`packages/data-spec/src/entry.ts`) is a moment and an optional point, with
  its words, photos, meal, bill, charge, device, subscription or check-in as parts by
  plugin. A device is on the day it was bought, what it sold for written on it once sold; a
  subscription on the day it was last paid, or for software bought once the day it was first
  written down. The use page lists both from these entries. A check-in is brought in from
  Swarm, its `source` that app's ids, or made on the phone from a stay of the footprints
  (`source.app` `phone`), private, from the stay's start to its `end`. It is named by its
  uid alone: its page is `/m/<uid>/` and its media sit under `moments/<uid>/` in R2, so its
  day, place and words can change after it is published. One without `published` is a draft
  the site skips; `published` blocks, the entry's and each media item's, never change. A
  device's `id` names one device, and a charge's `vehicle` names one of them; the API turns
  away a change set that breaks either.
- A **trip** is a collection taking the entries in its span (with `include` and
  `exclude`), its waypoints marks of their own (entries with a place and no parts).
- A **place** has a name, WGS-84 coordinates, a kind, the province, city and district it is
  in (`vp run places:locate`), an optional note, for a train's end its `station`, and for one
  brought in from Swarm the venue it was made for (`source`). Places are never merged for
  being near each other or sharing a name.
- Each record is public or private: its own `visibility`, else its container's, else its
  plugin's default (a bill is private), else public.
- Units are those `packages/data-spec/src/primitives.ts` gives: metres, watt-hours, seconds,
  and yuan where no `currency` is beside an amount. What happened keeps the offset it
  happened at; `createdAt` and `updatedAt` are written in UTC. A charge's `durationMin` is
  still written beside its `durationSeconds` for the apps installed before it.

`vp run data:check` checks `data/` as a whole, past what each record's schema sees: that
every entry and collection parses, that what they name is there, that device ids hold,
and it warns of fields the spec doesn't name, which the loose schemas let through.

## Building from the API

The site is built from the API, with `MOMENTS_API_TOKEN` in the environment or `.env`:
`apps/web/src/data/api-data.ts` fetches `/v2/export` once, writes it under
`apps/web/.artifacts/api-data/` in this layout, and points the store there. Without a
token, or with one that fails to fetch, the build fails; `HYOBAN_DATA_ROOT` set to a
directory in this layout builds from that instead, as the tests do with the fixture. The
scripts read `data/`.

## Writing outside the API

Anything that writes records into `data/` itself, like the create-moment skill, brings
`data/` up to date first and pushes what it wrote afterwards. Otherwise the site never shows
it, and the next `data:export` stops rather than overwrite it.

```sh
vp run data:export                                    # data/ from the API first
vp run data:push data/entries/2026/10/<uid>.json      # then the records written
```

`data:push` merges: it writes the entries and collections it is given, every place they
name and any `--place <id>`, and removes nothing, so what the app published meanwhile
stays. It writes the deployed database, then has the API fetch the new legs' routes and
start a site build (`POST /v2/build`), since a write past the Worker starts none.
`--local` writes the database `wrangler dev` uses, `--dry-run` only the SQL.

## Exporting and restoring

```sh
vp run data:export            # the API's export into data/
vp run data:import            # data/ into the local database `wrangler dev` uses
vp run data:import --remote   # data/ into the deployed one, to restore it
```

`data:export` needs `MOMENTS_API_TOKEN`. It writes canonical JSON and removes what the API
no longer has, so a run with nothing new changes nothing. It keeps the digest of each
record it wrote, and of each one `data:push` sent, in `data/.synced.json`, and stops when a
record in `data/` differs from those, or the car's statistics have changes git hasn't
committed: those may not be in the API yet.

`data:import` makes the database a mirror of `data/`, so use it only to restore: it applies
the migrations, writes new and changed records and marks the rest deleted, including what
the app published since that copy was exported. Only those take a new revision, so the app's
sync sees just the difference. `--dry-run` writes the SQL under `.artifacts/import/`. D1's
Time Travel also keeps 30 days of the database.
