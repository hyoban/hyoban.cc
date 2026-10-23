# Data

The Moments, places and trips live in the API's D1 database (`apps/api`), which the
recording app writes, committing what it kept on the phone as one change set, and the
site's build reads. `data/` keeps a backup of them as JSON
records, in the shape `packages/data-spec` defines: the data-backup workflow writes
the API's export into it every six hours and commits what changed, so edit the records
through the API rather than here. `devices.json` is the exception: it lives only here.

```
data/
├─ manifest.json           schemaVersion, generator, updatedAt
├─ places.json             places by id: name, WGS-84 coordinates, a kind, the province,
│                          city and district it is in (pnpm places:locate), an optional
│                          note, and for a train's end the station it leaves from
├─ routes.json             each trip leg's route by its key, as the API fetched it
├─ trips/<id>.json         a trip: dates, waypoints, straight legs, covers by uid
├─ moments/YYYY/MM/<ulid>.json
└─ schema/*.schema.json    generated from the spec by `pnpm data:schema`
```

A Moment names its place (one at most), carries its words and media, and once published has a
`published` block with its `YYYY/MM/DD-NN-slug` id; each media item's `published`
block holds the R2 key, ETag, size, dimensions and responsive variants. A Moment that
tells of a meal may carry a `food` block: the dishes as its words name them, a verdict
(`good`, `ok` or `bad`) and the bill with how many it fed. A record
without `published` is a draft the site skips. Published fields never change: a
different photo is a new media item with a new key.

## Building from the API

With `MOMENTS_API_TOKEN` set, in the environment or `.env`, the site is built from the
API instead of `data/`: `src/data/api-data.ts` fetches `/v1/export` once before
anything reads a record, writes it under `.artifacts/api-data/` in this layout, and
points the store and the Moments collection there. `MOMENTS_API_URL` overrides the
deployed address. Without a token the build reads `data/`, and a token that fails to
fetch fails the build. The scripts always read `data/`; `devices.json` stays here.

## Writing outside the API

Anything that writes records into `data/` itself, like the create-moment skill, has
to bring `data/` up to date first and push what it wrote afterwards. Otherwise the site never shows it, and the next backup removes it from
`data/` because the API doesn't have it.

```sh
pnpm data:export                                   # data/ from the API first
pnpm data:push data/moments/2026/10/<uid>.json     # then the records written, and their places
```

`data:push` merges: it writes the Moments and trips it is given, every place they
name and any `--place <id>`, over what the database has for them, and removes
nothing, so what the app published meanwhile stays. It writes the deployed database;
`--local` writes the one `wrangler dev` uses and `--dry-run` only the SQL.

## Backing up and restoring

```sh
pnpm data:export            # the API's export into data/, as the workflow does
pnpm data:import            # data/ into the local database `wrangler dev` uses
pnpm data:import --remote   # data/ into the deployed one, to restore it
```

`data:export` needs `MOMENTS_API_TOKEN`, in the environment or `.env`; the workflow
reads it from the repository's secrets. It writes each record in canonical JSON and
removes the Moments and trips the API no longer has, so a run with nothing new changes
nothing. It stops when `moments/`, `trips/`, `places.json` or `manifest.json` have
uncommitted changes, which may not be in the API yet. D1's Time Travel keeps 30 days
of the database too.

`data:import` makes the database a mirror of `data/`, so only restore with it: it
applies the migrations, then writes new and changed records and marks the ones no
longer under `data/` deleted, including what the app published since the backup; only those take the new revision, so the app's sync only
sees the difference. `--dry-run` only writes the SQL under `.artifacts/import/`.
