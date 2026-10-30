# hyoban.cc

The source of [hyoban.cc](https://hyoban.cc): a personal site built with Astro and
plain CSS, with posts, a calendar and map of Moments and trips, a search over both,
and the pieces around it that record and serve them.

```
.
├─ src/                 the Astro site (DESIGN.md describes how it looks)
├─ data/                JSON backup of the Moments, places, trips and routes (docs/data.md)
├─ apps/api/            Cloudflare Worker serving the records from D1 (@hyoban/api)
├─ apps/android/        这里, the Android app that reads and writes Moments (Gradle)
├─ packages/data-spec/  the Zod schemas every side shares (@hyoban/data-spec)
├─ packages/trip-map/   the map page and trip player (@hyoban/trip-map)
├─ scripts/             data, asset and media scripts, run as `vp run <name>`
└─ docs/                data.md, assets.md and deploy.md
```

## How it fits together

```
Android app ──POST /v2/changes──▶ API Worker (D1 + R2) ──deploy hook──▶ site build
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

| Variable              | Used for                                                              |
| --------------------- | --------------------------------------------------------------------- |
| `VITE_MAPBOX_TOKEN`   | the map page's Mapbox tiles                                           |
| `MAPBOX_STATIC_TOKEN` | the map pages' placeholders, if `VITE_MAPBOX_TOKEN` is URL-restricted |
| `MOMENTS_API_TOKEN`   | building from the API, `data:export` and `data:push`                  |
| `MOMENTS_API_URL`     | another API than `https://hyoban-cc-api.hyoban.workers.dev`           |
| `CI`                  | set, the build draws every Moment's share card (slow; off locally)    |

The scripts that upload to R2 use Wrangler's login, or `CLOUDFLARE_API_TOKEN`;
`media:generate` needs `TMDB_API_TOKEN` or `TMDB_API_KEY`.

To run the API locally, see [apps/api](apps/api/README.md); for the app,
[apps/android](apps/android/README.md), built with its own `./gradlew`.

## Deployment

Everything runs on Cloudflare and deploys from `main`: Workers Builds builds the site on
every push and whenever the API takes a write it shows, and deploys the API when its
inputs change; GitHub Actions back up the data and the R2 bucket. The settings, secrets
and recovery steps are in [docs/deploy.md](docs/deploy.md).

## Thanks

The 3D car's speed mode, held down in the year review and the 20,000 km post, where a line credits it
(`src/car-model-speed.ts`), follows [GameMCU](https://gamemcu.com)'s
[Xiaomi SU7 showroom](https://gamemcu.com/su7/): its tunnel of streaks, its reflections
laid crisp over the paint, its glossy floor and its camera.
