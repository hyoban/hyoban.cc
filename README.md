# hyoban.cc

The source of [hyoban.cc](https://hyoban.cc): a personal site built with Astro and
Tailwind v4, with posts, a calendar and map of Moments and trips, and the pieces
around it that record and serve them.

```
.
├─ src/                 the Astro site (DESIGN.md describes how it looks)
├─ data/                JSON backup of the Moments, places, trips and routes (docs/data.md)
├─ apps/api/            Cloudflare Worker serving the records from D1 (@hyoban/api)
├─ apps/android/        这里, the Android app that reads and writes Moments (Gradle)
├─ packages/data-spec/  the Zod schemas every side shares (@hyoban/data-spec)
├─ packages/trip-map/   the map page and trip player (@hyoban/trip-map)
├─ scripts/             data, asset and media scripts, run as `vp run <name>`
└─ docs/                data.md and assets.md
```

## How it fits together

```
Android app ──POST /v1/changes──▶ API Worker (D1 + R2) ──deploy hook──▶ site build
                                       │   ▲                                 │
                         data:export   │   │ data:push / data:import         ▼
                                       ▼   │                          hyoban.cc (static)
                                     data/ (backup in git)
```

- The **API** (`apps/api`) keeps the Moments, places, trips and the trips' routes in a
  D1 database, and the photos and videos in the `blog-images` R2 bucket, served from
  `https://image.hyoban.cc`.
- The **Android app** writes to it as one change set (`POST /v1/changes`) and reads
  its public endpoints.
- The **site** is static. Its build reads the API's export when `MOMENTS_API_TOKEN` is
  set, else the committed `data/`.
- **`data/`** is a backup: a workflow writes the API's export into it every six hours.
  Edit records through the API, or push what you wrote with `vp run data:push`
  (docs/data.md).

## Development

This is a pnpm workspace driven by [Vite+](https://viteplus.dev) (`vp`); see AGENTS.md.

```sh
vp install
vp run dev          # the site, from data/ unless .env has MOMENTS_API_TOKEN
vp check            # format, lint and type check, packages and API included
vp test
vp run build        # the static site into dist/
```

`.env` (see `.env.example`) may hold:

| Variable            | Used for                                                           |
| ------------------- | ------------------------------------------------------------------ |
| `VITE_MAPBOX_TOKEN` | the map page's Mapbox tiles                                        |
| `MOMENTS_API_TOKEN` | building from the API, `data:export` and `data:push`               |
| `MOMENTS_API_URL`   | another API than `https://hyoban-cc-api.hyoban.workers.dev`        |
| `CI`                | set, the build draws every Moment's share card (slow; off locally) |

The scripts that upload to R2 use Wrangler's login, or `CLOUDFLARE_API_TOKEN`;
`media:generate` needs `TMDB_API_TOKEN` or `TMDB_API_KEY`.

To run the API locally, see [apps/api](apps/api/README.md); for the app,
[apps/android](apps/android/README.md), built with its own `./gradlew`.

## Deployment

Everything runs on Cloudflare, under one account, and deploys from `main`.

### The site

A Worker with static assets (`wrangler.jsonc`: `hyoban-cc`, serving `./dist`),
deployed by **Workers Builds** from the repository root:

- build command `pnpm build`, deploy command `npx wrangler deploy`;
- build variables: `MOMENTS_API_TOKEN`, so the build reads the API rather than
  `data/`, and `VITE_MAPBOX_TOKEN`; Workers Builds sets `CI`, which turns on the
  Moments' share cards;
- share cards are cached between builds under `node_modules/.astro/share-cards`, which
  Cloudflare's build cache keeps, and redrawn only when what they show or
  `src/og-image.ts` changes.

It builds on every push to `main`, and again whenever the API takes a write the site
shows: the API calls the build's **deploy hook**, kept in the API's `DEPLOY_HOOK_URL`
secret. A token that can't fetch the export fails the build rather than falling back
to `data/`.

The custom domain `hyoban.cc` and the image origin `image.hyoban.cc` (the R2 bucket)
are on the same zone, which needs **Images transformations** enabled: the site asks it
for resized photos and video posters (docs/assets.md).

### The API

`apps/api`, at https://hyoban-cc-api.hyoban.workers.dev, also deployed by Workers
Builds:

- root directory `apps/api`, installing from the repository root, deploy command
  `pnpm run deploy`, which applies new D1 migrations (`migrations/`) and then deploys;
  the build's API token needs D1 Edit, and a failing migration stops the deploy;
- it builds only for pushes to `main` touching `apps/api/`, `packages/data-spec/`,
  `packages/trip-map/` or `pnpm-lock.yaml`;
- secrets, set with `wrangler secret put <name> --config apps/api/wrangler.jsonc`:

  | Secret            | What                                                     |
  | ----------------- | -------------------------------------------------------- |
  | `API_TOKENS`      | comma-separated bearer tokens for writes, drafts, export |
  | `DEPLOY_HOOK_URL` | the site's deploy hook, called after a write it shows    |
  | `MAPBOX_TOKEN`    | Mapbox Directions, for the trips' drive routes           |

- bindings in `apps/api/wrangler.jsonc`: D1 `DB` (`hyoban-cc`, APAC) and R2 `BUCKET`
  (`blog-images`).

Setting it up from nothing: `wrangler d1 create hyoban-cc --location apac`, put the new
id in `apps/api/wrangler.jsonc`, set the secrets, deploy, then restore the records with
`vp run data:import --remote`.

### GitHub Actions

| Workflow          | When                        | Does                                                                                                 |
| ----------------- | --------------------------- | ---------------------------------------------------------------------------------------------------- |
| `verify.yml`      | push to `main`, PRs, weekly | `pnpm test`, `pnpm check`, Moment audits, `pnpm build`; the Android app's unit tests and debug build |
| `data-backup.yml` | every six hours, by hand    | the API's export into `data/`, committed to `main` when it changed                                   |
| `r2-backup.yml`   | push to `main`, by hand     | a snapshot of the R2 bucket, in Git LFS on the `r2-backup` branch                                    |

Repository secrets: `MOMENTS_API_TOKEN` (one of the API's `API_TOKENS`) for the data
backup, and `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` for the R2 backup. Those, D1's
30 days of Time Travel and `vp run data:import --remote` are the way back from a bad
write.

### The Android app

Not deployed anywhere: build the release APK with `./gradlew` in `apps/android` and
install it on the phone. Its API token is set on its settings page.
