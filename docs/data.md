# Data

The Moments, places and trips live in the API's D1 database (`apps/api`), which the
Android app writes and the site's build reads. `data/` keeps a backup of them as JSON
records in the shape `packages/data-spec` defines: the data-backup workflow writes the
API's export into it every six hours and commits what changed, so edit records through
the API rather than here. `devices.json` and the Zeekr statistics in `data/zeekr/` (see
[its README](../data/zeekr/README.md)) live only in the repository.

```
data/
├─ manifest.json                 schemaVersion, generator, updatedAt
├─ entries/YYYY/MM/<ulid>.json   an entry
├─ collections/<slug>.json       a collection, such as a trip
├─ tracks/<ulid>.json            a public span of the footprints
├─ places.json                   places by id
├─ routes.json                   each trip leg's route by its key, as the API fetched it
└─ schema/*.schema.json          generated from the spec by `vp run data:schema`
```

- An **entry** (`packages/data-spec/src/entry.ts`) is a moment and an optional point, with
  its words, photos, meal, bill or charge as parts by plugin. It is named by its uid alone:
  its page is `/m/<uid>/` and its media sit under `moments/<uid>/` in R2, so its day, place
  and words can change after it is published. One without `published` is a draft the site
  skips; `published` blocks, the entry's and each media item's, never change.
- A **trip** is a collection taking the entries in its span (with `include` and
  `exclude`), its waypoints marks of their own (entries with a place and no parts).
- A **place** has a name, WGS-84 coordinates, a kind, the province, city and district it is
  in (`vp run places:locate`), an optional note, and for a train's end its `station`.
- Each record is public or private: its own `visibility`, else its container's, else its
  plugin's default (a bill is private), else private for tech.

Schema version 3 moved `data/` from the first shape (`moments/`, `trips/`) to this one.
`vp run data:migrate` shows such a move, `--write` makes it and `--rollback --write` undoes
it byte for byte; the store reads either layout.

## Building from the API

With `MOMENTS_API_TOKEN` set, in the environment or `.env`, the site is built from the API
instead: `src/data/api-data.ts` fetches `/v2/export` once, writes it under
`.artifacts/api-data/` in this layout, and points the store there. `MOMENTS_API_URL`
overrides the address. Without a token the build reads `data/`; a token that fails to fetch
fails the build. The scripts always read `data/`.

## Writing outside the API

Anything that writes records into `data/` itself, like the create-moment skill, brings
`data/` up to date first and pushes what it wrote afterwards. Otherwise the site never shows
it, and the next backup removes it.

```sh
vp run data:export                                    # data/ from the API first
vp run data:push data/entries/2026/10/<uid>.json      # then the records written
```

`data:push` merges: it writes the entries and collections it is given, every place they
name and any `--place <id>`, and removes nothing, so what the app published meanwhile
stays. It writes the deployed database, then has the API fetch the new legs' routes and
start a site build (`POST /v1/build`), since a write past the Worker starts none.
`--local` writes the database `wrangler dev` uses, `--dry-run` only the SQL.

## Backing up and restoring

```sh
vp run data:export            # the API's export into data/, as the workflow does
vp run data:import            # data/ into the local database `wrangler dev` uses
vp run data:import --remote   # data/ into the deployed one, to restore it
```

`data:export` needs `MOMENTS_API_TOKEN`. It writes canonical JSON and removes what the API
no longer has, so a run with nothing new changes nothing. It stops when `data/` has
uncommitted record changes, which may not be in the API yet.

`data:import` makes the database a mirror of `data/`, so use it only to restore: it applies
the migrations, writes new and changed records and marks the rest deleted, including what
the app published since the backup. Only those take a new revision, so the app's sync sees
just the difference. `--dry-run` writes the SQL under `.artifacts/import/`. D1's Time Travel
also keeps 30 days of the database.
