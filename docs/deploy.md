# Deployment

Everything runs on one Cloudflare account and deploys from `main`. Nothing is deployed by
hand: a push to `main` builds the site, and the API too when the push touches its inputs.

## The site

`apps/web` is a Worker with static assets (`apps/web/wrangler.jsonc`: `hyoban-cc`, serving
`./dist`), deployed by **Workers Builds**:

- Root directory `apps/web`, installing from the repository root. Build command
  `pnpm build`, deploy command `npx wrangler deploy`.
- Build variables:
  - `MOMENTS_API_TOKEN`, needed to read the API.
  - `VITE_MAPBOX_TOKEN`.
  - `MAPBOX_STATIC_TOKEN`, a token without URL restrictions for the map pages'
    placeholders. Without it they're left out.
  - Workers Builds sets `CI`, which turns on the Moments' share cards.
- Share cards are cached between builds under `apps/web/node_modules/.astro/share-cards`
  (their assets under `share-assets`), which Cloudflare's build cache keeps. They are
  redrawn only when what they show or `apps/web/src/og-image.ts` changes.

The site builds on every push to `main`, and again whenever the API takes a write the site
shows: the API calls the build's **deploy hook**, stored in its `DEPLOY_HOOK_URL` secret.
Without a token that can fetch the export, the build fails
([data.md](data.md#building-from-the-api)).

Each build reads `/media/music/` from the NetEase Cloud Music playlist in
`site.config.json` (`music`), through its public API with no login. It keeps the last copy
in the build cache under `apps/web/node_modules/.astro`. When NetEase can't be reached it
uses that copy; with no copy the build fails.

The domain `hyoban.cc` and the image origin `image.hyoban.cc` (the R2 bucket) share one
zone, which needs **Images transformations** enabled for resized photos and video posters
([assets.md](assets.md)).

## The API

`apps/api`, at https://hyoban-cc-api.hyoban.workers.dev, also deployed by Workers Builds:

- Root directory `apps/api`, installing from the repository root. Deploy command
  `pnpm run deploy`, which applies new D1 migrations (`migrations/`) before deploying the
  code that needs them. The build's API token needs D1 Edit; a failing migration stops the
  deploy.
- It builds only for pushes to `main` touching `apps/api/`, `packages/data-spec/`,
  `packages/trip-map/` or `pnpm-lock.yaml`. Keep such changes compatible with the app
  already installed on the phone.
- Secrets, set with `wrangler secret put <name> --config apps/api/wrangler.jsonc`:

  | Secret               | What                                                   |
  | -------------------- | ------------------------------------------------------ |
  | `API_TOKENS`         | comma-separated bearer tokens; every request needs one |
  | `DEPLOY_HOOK_URL`    | the site's deploy hook, called after a write it shows  |
  | `TELEGRAM_BOT_TOKEN` | the bot that posts new Moments to the Telegram channel |

- Bindings in `apps/api/wrangler.jsonc`: D1 `DB` (`hyoban-cc`, APAC), R2 `BUCKET`
  (`blog-images`), and a cron trigger every 15 minutes.
- Vars there too:
  - `TELEGRAM_CHAT_ID`, the channel. Its bot must be an admin allowed to post and delete.
  - `DIVISIONS_URL`, DataV's GeoAtlas, used to look up a new place's province, city and
    district.

The Worker is minified and has observability on, so its logs are kept in the dashboard.
Stream them with `npx wrangler tail hyoban-cc-api`.

## GitHub Actions

| Workflow              | When                        | Does                                                                                                                                                |
| --------------------- | --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `verify.yml`          | push to `main`, PRs, weekly | `pnpm test`, `pnpm check`, Moment audits, `pnpm build`; the Android app's unit tests, debug build and `:desktop` classes; HereKit's tests and icons |
| `android-release.yml` | tag `android-v*`            | the signed APK in a GitHub release, which the app updates itself from                                                                               |
| `mac-release.yml`     | tag `mac-v*`                | the Mac app's DMG in a GitHub release                                                                                                               |

`verify.yml` exports the API into `data/` to audit every Moment and builds the site from the
API, caching share cards in the Actions cache. Its weekly run also checks every referenced
R2 object (`moments:audit --remote`). The workflows run Node 24; the repository pins no Node
version, so Workers Builds uses its own default.

Repository secrets: `MOMENTS_API_TOKEN` (one of the API's `API_TOKENS`) for the export and
build in `verify.yml`, and the `ANDROID_RELEASE_*` signing secrets for
`android-release.yml` ([the app's README](../apps/android/README.md#releasing)).

## Recovering from a bad write

D1's 30 days of Time Travel is the way back. An earlier exported `data/` can also be
restored over the deployed database with `vp run data:import --remote` ([data.md](data.md)).

A Time Travel restore also restores `meta`'s `database` value. The app's copy, which is
ahead of the restore, would then continue from rows the database no longer has. Set a new
value from `apps/api` so every copy rereads all rows:

```bash
npx wrangler d1 execute hyoban-cc --remote --command "UPDATE meta SET value = CAST(abs(random() % 9007199254740991) AS TEXT) WHERE key = 'database'"
```

## Setting it up from nothing

The API comes first: the site's build reads it. The API needs the site's deploy hook, which
exists only once the site's build does.

1. Fill in `site.config.json` ([FORKING.md](../FORKING.md)). In the account named in
   `assets`, create the R2 bucket `blog-images` and connect `image.hyoban.cc` to it.
2. Run `wrangler d1 create hyoban-cc --location apac` and put the new id in
   `apps/api/wrangler.jsonc`.
3. Create the Telegram bot and make it an admin of the channel.
4. Set the API's secrets except `DEPLOY_HOOK_URL`, connect `apps/api` to Workers Builds as
   above, and push to `main`.
5. Restore the records from an exported `data/` with `vp run data:import --remote`
   ([data.md](data.md)).
6. Connect `apps/web` to Workers Builds as the `hyoban-cc` Worker, with `MOMENTS_API_TOKEN`
   set to one of the API's tokens and the Mapbox variables. Add the custom domain
   `hyoban.cc` and enable Images transformations on its zone.
7. Create the site build's deploy hook and set it as the API's `DEPLOY_HOOK_URL`.
8. In GitHub, set the repository secrets above.

## The apps

Release by pushing an `android-v<x.y.z>` or `mac-v<x.y.z>` tag
([the app's README](../apps/android/README.md#releasing)). Set the API and its token in the
app under 我 → 同步与来源.
