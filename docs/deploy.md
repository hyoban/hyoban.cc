# Deployment

Everything runs on Cloudflare, under one account, and deploys from `main`. Nothing is
deployed by hand: a push to `main` builds the site, and the API too when it touches
the API's inputs.

## The site

`apps/web`, a Worker with static assets (`apps/web/wrangler.jsonc`: `hyoban-cc`,
serving `./dist`), deployed by **Workers Builds**:

- root directory `apps/web`, installing from the repository root, build command
  `pnpm build`, deploy command `npx wrangler deploy`;
- build variables: `MOMENTS_API_TOKEN`, which the build needs to read the API,
  `VITE_MAPBOX_TOKEN`, and `MAPBOX_STATIC_TOKEN`, a token without URL
  restrictions that draws the map pages' placeholders (without it they're left out);
  Workers Builds sets `CI`, which turns on the Moments' share cards;
- share cards are cached between builds under `apps/web/node_modules/.astro/share-cards`,
  which Cloudflare's build cache keeps, and redrawn only when what they show or
  `apps/web/src/og-image.ts` changes.

It builds on every push to `main`, and again whenever the API takes a write the site
shows: the API calls the build's **deploy hook**, kept in the API's `DEPLOY_HOOK_URL`
secret. Without a token, or with one that can't fetch the export, the build fails: the
repository keeps no copy of the records.

Each build also reads `/media/`'s 音乐 tab from the NetEase Cloud Music playlist named
in `apps/web/src/music.ts`, from its public API with no login, and keeps the last copy
in the build cache under `apps/web/node_modules/.astro`; when NetEase can't be reached
it shows that copy, and with none the build fails.

The custom domain `hyoban.cc` and the image origin `image.hyoban.cc` (the R2 bucket)
are on the same zone, which needs **Images transformations** enabled: the site asks it
for resized photos and video posters ([assets.md](assets.md)).

## The API

`apps/api`, at https://hyoban-cc-api.hyoban.workers.dev, also deployed by Workers
Builds:

- root directory `apps/api`, installing from the repository root, deploy command
  `pnpm run deploy`, which applies new D1 migrations (`migrations/`) before it deploys
  the code that needs them; the build's API token needs D1 Edit, and a failing
  migration stops the deploy;
- it builds only for pushes to `main` touching `apps/api/`, `packages/data-spec/`,
  `packages/trip-map/` or `pnpm-lock.yaml`, so keep such changes compatible with the
  app already installed on the phone;
- secrets, set with `wrangler secret put <name> --config apps/api/wrangler.jsonc`:

  | Secret               | What                                                     |
  | -------------------- | -------------------------------------------------------- |
  | `API_TOKENS`         | comma-separated bearer tokens for writes, drafts, export |
  | `DEPLOY_HOOK_URL`    | the site's deploy hook, called after a write it shows    |
  | `TELEGRAM_BOT_TOKEN` | the bot that posts new Moments to the Telegram channel   |

- bindings in `apps/api/wrangler.jsonc`: D1 `DB` (`hyoban-cc`, APAC) and R2 `BUCKET`
  (`blog-images`), and a cron trigger every 15 minutes;
- vars there too: `TELEGRAM_CHAT_ID`, the channel, whose bot must be an admin allowed
  to post and delete, and `DIVISIONS_URL`, DataV's GeoAtlas, where a new place's
  province, city and district are looked up.

The Worker is minified and has observability on, so its logs are kept in the
dashboard; they stream with `npx wrangler tail hyoban-cc-api`.

## GitHub Actions

| Workflow        | When                        | Does                                                                                                 |
| --------------- | --------------------------- | ---------------------------------------------------------------------------------------------------- |
| `verify.yml`    | push to `main`, PRs, weekly | `pnpm test`, `pnpm check`, Moment audits, `pnpm build`; the Android app's unit tests and debug build |
| `r2-backup.yml` | push to `main`, by hand     | a snapshot of the R2 bucket, in Git LFS on the `r2-backup` branch                                    |

`verify.yml` exports the API into `data/` to audit every Moment, builds the site from the
API, its share cards kept between runs in the Actions cache as Workers Builds keeps them,
and on its weekly run also checks every referenced R2 object (`moments:audit
--remote`). The records aren't backed up to GitHub. The workflows run Node 24; the repository pins no Node version, so Workers
Builds uses its own default.

`r2-backup.yml` checks out the existing `r2-backup` branch and commits to it, so that
branch has to exist, and the snapshot counts against the repository's Git LFS storage.

Repository secrets: `MOMENTS_API_TOKEN` (one of the API's `API_TOKENS`) for the export
and the build in `verify.yml`, and `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` for the R2 backup.

## Recovering from a bad write

D1's 30 days of Time Travel and the R2 snapshot on the `r2-backup` branch are the way
back; the records have no copy in git. A `data/` exported earlier can be restored over the
deployed database with `vp run data:import --remote` ([data.md](data.md)).

A restore with Time Travel puts back `meta`'s `database` too, and the app's copy, ahead of the
restore, would carry on from rows the database no longer has. Give it a new one from `apps/api`, so every copy
reads all the rows again:

```bash
npx wrangler d1 execute hyoban-cc --remote --command "UPDATE meta SET value = CAST(abs(random() % 9007199254740991) AS TEXT) WHERE key = 'database'"
```

## Setting it up from nothing

The API comes first: the site's build reads it, and the API needs the site's deploy
hook, which exists only once the site's build does.

1. In the account `apps/web/src/data/asset-config.json` names, create the R2 bucket
   `blog-images` and connect `image.hyoban.cc` to it.
2. `wrangler d1 create hyoban-cc --location apac`, and put the new id in
   `apps/api/wrangler.jsonc`.
3. Create the Telegram bot and make it an admin of the channel.
4. Set the API's secrets, all but `DEPLOY_HOOK_URL`, connect `apps/api` to Workers
   Builds as above and push to `main`.
5. Restore the records from an exported `data/` with `vp run data:import --remote`
   ([data.md](data.md)).
6. Connect `apps/web` to Workers Builds as the `hyoban-cc` Worker, with
   `MOMENTS_API_TOKEN` set to one of the API's tokens and the Mapbox variables, add
   the custom domain `hyoban.cc`, and enable Images transformations on its zone.
7. Create the site build's deploy hook and set it as the API's `DEPLOY_HOOK_URL`.
8. In GitHub, set the repository secrets above and create the `r2-backup` branch.

## The Android app

Not deployed anywhere: build the release APK with `./gradlew :app:assembleRelease` in
`apps/android` and install it on the phone with `adb install -r`
([its README](../apps/android/README.md)). Its API token is set on its settings page.
