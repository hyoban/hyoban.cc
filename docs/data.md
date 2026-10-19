# Data

The Moments, places and trips live in the API's D1 database (`apps/api`), which the
recording app writes and the site's build reads. `data/` keeps a backup of them as JSON
records, in the shape `packages/data-spec` defines: the data-backup workflow writes
the API's export into it every six hours and commits what changed, so edit the records
through the API rather than here. `devices.json` is the exception: it lives only here.

```
data/
├─ manifest.json           schemaVersion, generator, updatedAt
├─ places.json             places by id: name, WGS-84 coordinates, an optional note
├─ trips/<id>.json         a trip: dates, waypoints, straight legs, covers by uid
├─ moments/YYYY/MM/<ulid>.json
└─ schema/*.schema.json    generated from the spec by `pnpm data:schema`
```

A Moment names its places, carries its words and media, and once published has a
`published` block with its `YYYY/MM/DD-NN-slug` id; each media item's `published`
block holds the R2 key, ETag, size, dimensions and responsive variants. A record
without `published` is a draft the site skips. Published fields never change: a
different photo is a new media item with a new key.

## Publishing a bundle

The app exports a bundle: the records in the layout above, without their `local`
fields, plus `media/<media uid>.<extension>` for every media item that isn't
published yet (a live photo has an image and a video with the same uid).

```sh
pnpm data:publish <bundle.zip>
```

The command refreshes `data/` from the API (it stops if `data/` has uncommitted
changes, which the refresh would overwrite), unpacks the bundle under
`.artifacts/publish/<time>/`, then:

1. Matches the bundle's places to `data/places.json`. A place within 200 m of an
   existing one, or with the same name, takes the existing id; the rest are added.
   An id already in the repository keeps the repository's coordinates.
2. Writes the trips, with places renamed.
3. Gives each unpublished Moment an id: the slug the record proposes in `slug`,
   else its first place, else its first words; the order is the next one that day.
4. Optimizes and uploads each unpublished media item through
   `scripts/lib/moment-media.mjs`, naming files by the slug.
5. Writes the records into `data/`. A record whose uid is already there keeps the
   `published` blocks it has and takes everything else from the bundle. A record
   with media missing from `media/` is skipped and listed.
6. Pushes those records, and the places they name, into the API's database
   (`--local`: the one `wrangler dev` uses), as `pnpm data:push` does.
7. Writes `receipt.zip` next to the bundle: the published records, the places they
   name, and `renamedPlaces` in its manifest. Share it back to the app.

Nothing is committed. Afterwards, write the alt texts the command lists, run
`pnpm trips:routes` and `pnpm trips:weather`, then `pnpm test`, `pnpm check` and
`pnpm build`, and push the records again with the `pnpm data:push` line it prints.
`--dry-run` only reports the place matching, without refreshing or writing anything.

The rules for places, ids and file names are `@hyoban/data-spec/publish`, which the
API's own publishing shares.

`scripts/fixtures/bundle` is a sample bundle; `scripts/lib/publish-data.test.mjs`
publishes it into a temporary repository.

## Building from the API

With `MOMENTS_API_TOKEN` set, in the environment or `.env`, the site is built from the
API instead of `data/`: `src/data/api-data.ts` fetches `/v1/export` once before
anything reads a record, writes it under `.artifacts/api-data/` in this layout, and
points the store and the Moments collection there. `MOMENTS_API_URL` overrides the
deployed address. Without a token the build reads `data/`, and a token that fails to
fetch fails the build. The scripts always read `data/`; `devices.json` stays here.

## Writing outside the API

Anything that writes records into `data/` itself, like `data:publish` or the
create-moment skill, has to bring `data/` up to date first and push what it wrote
afterwards. Otherwise the site never shows it, and the next backup removes it from
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
