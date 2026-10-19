# Data

The Moments, places and trips live under `data/` as JSON records, in the shape
`packages/data-spec` defines. The recording app is the source of these records; the
repository keeps the copy the site is built from, and the scripts here only ever fill
in what publishing adds.

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

The command unpacks the bundle under `.artifacts/publish/<time>/`, then:

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
6. Writes `receipt.zip` next to the bundle: the published records, the places they
   name, and `renamedPlaces` in its manifest. Share it back to the app.

Nothing is committed. Afterwards, write the alt texts the command lists, run
`pnpm trips:routes` and `pnpm trips:weather`, then `pnpm test`, `pnpm check` and
`pnpm build`. `--dry-run` only reports the place matching.

`scripts/fixtures/bundle` is a sample bundle; `scripts/lib/publish-data.test.mjs`
publishes it into a temporary repository.

## Building from the API

With `MOMENTS_API_TOKEN` set, in the environment or `.env`, the site is built from the
API instead of `data/`: `src/data/api-data.ts` fetches `/v1/export` once before
anything reads a record, writes it under `.artifacts/api-data/` in this layout, and
points the store and the Moments collection there. `MOMENTS_API_URL` overrides the
deployed address. Without a token the build reads `data/`, and a token that fails to
fetch fails the build. The scripts always read `data/`; `devices.json` stays here.

## The API's copy

`apps/api` serves the same records from D1. Until the app writes to it directly,
`data/` stays the source:

```sh
pnpm data:import            # the local database `wrangler dev` uses
pnpm data:import --remote   # the deployed one
```

It applies the migrations, then writes new and changed records and marks the ones no
longer under `data/` deleted; only those take the new revision, so the app's sync only
sees the difference. `--dry-run` only writes the SQL under `.artifacts/import/`.
