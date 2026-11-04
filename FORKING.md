# Making it your own

The repository is one person's site, API and Android app, but nothing in the code is tied to
that person: who the site belongs to and where it lives is in one file,
[`site.config.json`](site.config.json), and the rest of what is personal is content to
replace. A fork changes that file, swaps the content, and deploys to its own Cloudflare
account.

## 1. Run it with made-up records

```sh
vp install
vp run dev
```

Without `MOMENTS_API_TOKEN` a local build or dev server reads the made-up records the tests
use, `packages/data-spec/test/fixtures/data/`, and draws plain tiles for their photos in the
share cards (their photos were never uploaded, so the pages' own images don't load). That is
enough to see every page before there is an API. In CI, where the real site builds, a
missing token still fails the build.

## 2. Change `site.config.json`

| Key                       | What                                                                                              |
| ------------------------- | ------------------------------------------------------------------------------------------------- |
| `site.url`                | the site's address; its host is printed on share cards and pictures                               |
| `site.title`, `tagline`   | the site's name, in every page's title, and the line under it                                     |
| `site.author`             | the name on posts and share pictures                                                              |
| `site.description`        | the home page's and feed's description                                                            |
| `site.telegram`           | the Telegram channel posts name in their `telegram:channel` meta, or `""` for none                |
| `site.links`              | the footer's links: `href`, `label` and a UnoCSS icon class                                       |
| `music.neteasePlaylistId` | the NetEase Cloud Music playlist `/media/music/` lists, or `null` for no music                    |
| `assets`                  | the Cloudflare account id, the R2 bucket and the origin that serves it ([assets](docs/assets.md)) |
| `api.url`                 | the API the site and scripts read, unless `MOMENTS_API_URL` names another                         |
| `github`                  | `owner/name` of the repository whose releases the Android app updates from                        |
| `android.applicationId`   | the Android app's id; a fork's must differ to install beside the original                         |

The site, the scripts, the API and the Android build all read it, so nothing else needs the
same values again.

## 3. Replace the content

- **Posts and pages**: `apps/web/src/content/posts/` and `apps/web/src/content/pages/use.md`.
  Their images live in R2 under `posts/<slug>/`; `apps/web/src/data/post-images.json` lists
  their sizes (`vp run posts:variants`).
- **Site images** in R2 under `site/`: `avatar-64.png`, `avatar-80.webp` and
  `avatar-cropped.png`.
- **The car**: `data/zeekr/` holds the original's car statistics and is the only record kept
  in git. Delete it; without car records the site leaves out the car, its 3D model and its
  driving statistics, though its code stays.
- **Records**: Moments, places and trips live only in your API's D1. Start empty, write them
  from the Android app, or import an exported `data/` ([data.md](docs/data.md)).

## 4. Deploy

[docs/deploy.md](docs/deploy.md), _Setting it up from nothing_, walks through the Cloudflare
account: the R2 bucket and its domain, the D1 database, the API's secrets and the two Workers
Builds. Besides `site.config.json`, rename what is Cloudflare's to name:

- `apps/web/wrangler.jsonc`: the site Worker's `name`;
- `apps/api/wrangler.jsonc`: the API Worker's `name`, D1's `database_name` and
  `database_id`, the R2 `bucket_name` (as `assets.bucket`) and `TELEGRAM_CHAT_ID`, the
  channel new Moments are posted to (leave `TELEGRAM_BOT_TOKEN` unset to post nowhere).

## 5. The Android app

Set `android.applicationId` and `github`, then build it in `apps/android` with its own
`./gradlew` ([its README](apps/android/README.md)). The app has no API of its own built in:
its first run asks for the API's address and token. Releases are signed with your own key,
in `local.properties` or the repository's secrets; the code keeps its package,
`cc.hyoban.moments`, whatever the id.
