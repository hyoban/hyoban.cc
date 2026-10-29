# Data

The Moments, places and trips live in the API's D1 database (`apps/api`), which the
recording app writes, committing what it kept on the phone as one change set, and the
site's build reads. `data/` keeps a backup of them as JSON
records, in the shape `packages/data-spec` defines: the data-backup workflow writes
the API's export into it every six hours and commits what changed, so edit the records
through the API rather than here. `devices.json` and the Zeekr captures in
`data/zeekr/` live only in the repository. The latter keep the car app's redacted
monthly reports, daily figures, charging sessions and assisted-driving statistics;
see `data/zeekr/README.md` for coverage and validation. The API backup leaves this
directory alone.

The vehicle schemas in `packages/data-spec/src/vehicle.ts` define the normalized
month records and cumulative assisted-driving observations. `vp run zeekr:convert`
rebuilds them from the statistical sources registered in `data/zeekr/imports.json`
without rewriting sources. `vp run zeekr:check` verifies privacy rules, hashes, source
values, schema, reconciliations and exact reproducibility of the saved conversion.
Unmodified captures and report-access links stay in the ignored, owner-only
`.private/zeekr/` directory. `vp run zeekr:prepare <private-capture.json> <device-id>`
creates a statistical projection with identity, location and access links removed.
Daily statistics and precise charging times are published with the owner's
authorization. Vehicle state stays only in private originals; the model stores
usage, charging and assistance. These records continue to live only in the repository.

Since schema version 3 the records are in a second shape (`packages/data-spec/src/entry.ts`):
an entry is a moment and an optional point, its words, photos, meal, bill or charge parts
by plugin; a trip is a collection taking the entries in its span (with `include` and
`exclude`), its old id kept as its `slug`, its waypoints marks of their own (entries with a
place and no parts); a public track is a span of the footprints made public, simplified.
Each is public or private: its own `visibility`, else its container's (a bill's book, on the
phone), else its plugin's default (a bill is private), else the entry's (private for tech).
`vp run data:migrate` shows what moving `data/` to it would do, `--write` moves it, checking
that every record comes back exactly, and `--rollback --write` moves it back, byte for byte.
The store reads either layout, and both at once while a script still writes Moments.

```
data/
├─ manifest.json           schemaVersion, generator, updatedAt
├─ entries/YYYY/MM/<ulid>.json   an entry (the second shape)
├─ collections/<slug>.json       a collection, a trip (the second shape)
├─ tracks/<ulid>.json            a public span of the footprints (the second shape)
├─ places.json             places by id: name, WGS-84 coordinates, a kind, the province,
│                          city and district it is in (pnpm places:locate), an optional
│                          note, and for a train's end the station it leaves from
├─ routes.json             each trip leg's route by its key, as the API fetched it
├─ trips/<id>.json         a trip: dates, waypoints, straight legs, covers by uid (the first shape)
├─ moments/YYYY/MM/<ulid>.json   a Moment (the first shape)
└─ schema/*.schema.json    generated from the spec by `pnpm data:schema`
```

A Moment names its place (one at most), carries its words and media, and once published has a
`published` block with when it was; each media item's `published` block holds the R2
key, ETag, size, dimensions and responsive variants. A Moment is named by its uid
alone: its page is `/m/<uid>/` and its media sit under `moments/<uid>/`, each file
named by its media item's uid (`getMediaKey`), so its day, places and words can all
change after it is published. A Moment that
tells of a meal may carry a `food` block: the dishes as its words name them, a verdict
(`good`, `ok` or `bad`) and the bill with how many it fed. A record
without `published` is a draft the site skips. Published blocks never change: a
different photo is a new media item with a new key.

## Building from the API

With `MOMENTS_API_TOKEN` set, in the environment or `.env`, the site is built from the
API instead of `data/`: `src/data/api-data.ts` fetches `/v2/export` (`/v1/export` from an
API without it) once before
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
nothing, so what the app published meanwhile stays. It writes the deployed database, then
has the API fetch the new legs' routes and call the site's deploy hook (`POST /v1/build`),
since a write past the Worker starts no build of its own;
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
