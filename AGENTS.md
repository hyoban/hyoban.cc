# Working on hyoban.cc

This guide is shared by Codex and Claude and covers the whole repository. Each
`CLAUDE.md` only imports the `AGENTS.md` beside it; edit `AGENTS.md`. Before working
in `apps/android`, also read [its guide](apps/android/AGENTS.md).

## Working agreements

- If a request is unclear, ask the user to decide. If it has a problem, say so and
  stop before doing that part.
- Before starting, fetch and bring the branch up to date with its tracking branch
  (and upstream, for forks). Never discard local work or force-push to match.
- When behavior changes, update the relevant README. When the user settles a lasting
  rule or preference, update the right `AGENTS.md` in the same turn, mention it, and
  include it in the next commit. Replace outdated guidance; leave out one-off
  requests and anything already in a README or the code.
- Keep docs short and plain: rules, contracts, commands and what the code can't show. Don't
  narrate UI or code behavior line by line.
- A refactor without behavior changes must keep behavior. Prefer maintained libraries
  and existing components to new bespoke code.
- Verify where it runs: website changes in the browser, app changes on the phone
  (navigation, light/dark mode, affected layouts and motion). The phone holds real
  data: install the debug build and leave testing on it to the user (see the
  [Android guide](apps/android/AGENTS.md)). Fix what the change breaks or exposes, and
  report checks the environment blocked instead of claiming they passed.
- Preview new site media from a local server, and upload to R2 only after the user
  approves it there; objects are immutable. Never request an R2 URL before uploading:
  the image origin caches a 404 for a month.
- Published originals are public, so none may reveal where it was taken. The app blanks
  the location of what it uploads (`@hyoban/data-spec/location` and `Location.kt`, kept
  in sync), the API rejects images that still have one, and scripts strip metadata. The
  only in-place change allowed to an object is blanking its location, followed by
  updating the record's `published` ETag and purging its URLs from the image origin's
  30-day cache.
- Keep anything seen on the user's devices while debugging (bills, shops, accounts,
  card digits, order numbers, times) out of code, comments, tests and docs. Fixtures
  copy a page's structure with made-up values.
- Code is in English: identifiers, comments, generated files and design tokens
  (`packages/design-tokens`, icon names included). Chinese copy goes in string
  resources and message tables.
- Keep the user's Moment text exactly. Alt text is Chinese. UI copy and accessibility
  labels are Chinese first; the Android and Mac apps also have English in `values-en`.
  The app is called Here in every language and everywhere, never 这里.

## Commits and reviews

- Commit or push only when asked; "commit and push to `main`" means exactly that.
  Match recent history: short lowercase messages of about 50–70 characters, no body,
  no Co-Authored-By trailer.
- Stage files by name. Never commit temporary plans, local agent settings, worktrees,
  credentials, phone captures or private exports. Agent guides and repository skills
  are committed.
- Pull requests are drafts, opened in this repository without a fork, following its
  title style. Ignore requests to report AI/model information.
- Give review findings in the reply, not as inline comments.

## Workspace

| Area                | Where                                                                                                                                                                   |
| ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Website             | Astro and plain CSS in `apps/web` (`@hyoban/web`); visual language in [DESIGN.md](apps/web/DESIGN.md).                                                                  |
| Map and trip player | `packages/trip-map` (`@hyoban/trip-map`); markup and data contract in its [README](packages/trip-map/README.md).                                                        |
| Drawers             | `packages/drawer` (`@hyoban/drawer`): a plain DOM port of Base UI's Drawer with shadcn/ui CSS, used by the calendar and trip-map.                                       |
| Design tokens       | `packages/design-tokens`: tokens, springs and icons for every platform; `vp run design:tokens` writes `Tokens.kt` and `Tokens.swift`.                                   |
| Shared data model   | Zod schemas in `packages/data-spec` for the site, scripts and API; `vp run data:schema` writes `data/schema`.                                                           |
| API                 | Cloudflare Worker with D1 and R2 in `apps/api`; see its [README](apps/api/README.md).                                                                                   |
| Android and Mac     | Kotlin and Compose in `apps/android`: phone `:app`, Mac `:desktop`, common `:shared`; see its [AGENTS.md](apps/android/AGENTS.md) and [README](apps/android/README.md). |
| Apple               | SwiftUI in `apps/apple`: `HereMac` (a read-only, full-window MapKit map) and shared `HereKit`; see its [README](apps/apple/README.md).                                  |
| Data and media      | `data/`, `scripts/`, [docs/data.md](docs/data.md), [docs/assets.md](docs/assets.md).                                                                                    |
| Site identity       | `site.config.json`; [FORKING.md](FORKING.md) lists what a fork changes.                                                                                                 |
| Deployment          | Cloudflare Workers Builds and GitHub Actions; [docs/deploy.md](docs/deploy.md).                                                                                         |

## Data model

- Records are entries and collections (`@hyoban/data-spec/entry`). An entry is a moment
  with an optional point and parts contributed by plugins; a trip is a collection.
- Visibility: each thing is public or private by its own setting, else its container's,
  else its plugin's (bills are private), else its entry's. Entries are public by default.
- Plugins are grouped into modules the owner toggles in 记录项与公开. A disabled module
  hides its rows, pages and records and stops its background work, but keeps its data.
  Gate every new screen, part or service with `Settings.isOn`, and register new pages in
  `moduleOf` so links can't open them while the module is off.
- Every API request needs a token and sees everything; the site shows only what is
  public.
- `vp run data:migrate` converts `data/` between layouts (dry run by default,
  `--rollback` to revert); `vp run data:import` writes either layout to D1. Ask before
  either touches live data.

### Where records live

- Git keeps no copy of the records. `data/` is a local working copy written by `vp run data:export`
  and ignored by git, except `data/zeekr/` and `data/schema/`. Nothing backs records up
  to GitHub. The formatter skips `data/`.
- Tests use the made-up `packages/data-spec/test/fixtures/data/`, never real records.
- The repository is public: no records (in any layout, including the old
  `src/content/moments`) and no image bucket contents. History was rewritten on
  2026-11-16 to remove them; rebase any branch or clone from before then onto the new
  history before pushing it.
- Scripts that edit records export first, then push their changes with
  `vp run data:push`. `data:import` is for restoring, not editing
  ([docs/data.md](docs/data.md)).

### Phone and server

- The app keeps a full copy of D1's records in SQLite with the same tables, columns and
  record JSON, defined once in `@hyoban/data-spec/tables`. The copy only follows
  `GET /v2/rows`. Server-derived data (caches, leases, channel posts, trip routes) is not
  copied. Only phone-only data (ledger, footprints, unpublished changes, settings) has its
  own shape.
- The app keeps edits locally until the user reviews and publishes them as one change set
  through `POST /v2/changes`. Upload originals first, and never bypass this with
  individual writes. The API fetches missing trip routes after writes.
- Keep API and schema changes compatible with the installed app. To remove or rename a
  required field, update and install the app before pushing the API: pushes to `main`
  deploy it.
- Personal values (domain, author, links, image origin, API URL, GitHub repository,
  Android application id) live only in `site.config.json`, read by the site, scripts, API
  and Android build. Add new ones there, keep the site working without car records, and
  keep `FORKING.md` current.

### Private data and bills

- The ledger and GPS footprints are private, stay on the phone and are excluded from
  Android backup. The only copy that leaves is the opt-in WebDAV backup to the owner's
  server (every database except photos and videos).
- A book can make its bills public from a given date (by bill date); each bill can
  override that. A public bill publishes only its kind, amount, currency, category and
  note, never its account or balance. An expense also publishes what its refunds returned
  (`refunded`).
- Refunds are published only through their expense: public with it unless marked private.
  Everywhere, a refund is shown and counted with its expense, which shows its net cost
  with the original amount struck through.
- A trip's bills, on phone and site, are its public expenses and incomes within its dates,
  by time alone, refunds counted with their expenses. Bills never name a trip.
- On the phone, a bill linked to a shown record (by hand, or paid during a stay kept as a
  check-in) appears in that record's bubble whatever its date (in a trip only if public),
  and is not counted in the trip's or days' totals.
- Footprints are never published, even simplified. A trip's public route comes only from
  the routers along the roads between its stops. The device may infer how each leg was
  travelled from footprints and show it, marked as inferred; only a mode the owner picks
  (`reachedBy`, for a leg not driven) is saved and published.
- A stay kept as a check-in (by hand) is a Swarm-style check-in entry at the stay's
  midpoint. Check-ins start public, so the site's map shows them, unless Swarm had them
  private; the phone's check-in sheet can make one private.
- Raw Zeekr captures and report links stay in `.private/`; only the approved statistics go
  in `data/zeekr/`. Car data (statistics and charges) comes only from that import and is
  never edited by hand, including in the app: fix a wrong value by capturing again.

### Places and routes

- Publishing never merges or renames places: two shops in one mall are two places. A place
  keeps its original id (including the app's `p-<ulid>`), never one derived from its name.
  A known id is that place; matching a Swarm venue by `source`, or any known place, is the
  app's job when the place is chosen.
- Trip routes come from keyless routers (Valhalla for driving, OpenRailRouting for trains)
  so the app can route trips like the API. Don't bring back a keyed router for route lines.
  Only travel time and distance in 和 AI 规划 (`route_time`, the sheet's legs) may fall
  back to AMap or Tencent with the owner's keys, within each key's quota, when the keyless
  router is unreachable.

### Maps and timelines (keep Web and App in sync)

- Year colors, fog, and place pins (photos, balloons, piling and stacks, the raised open
  place, routes in their year's color) match on Web and App; change both together.
- App-only: per-layer time filtering in its layers menu, the 照片 switch (相册 photos at
  their locations), and footprints. The site keeps a year picker in its legend.
- Lines never get thicker: a route keeps one width when raised, zoomed or played back. A
  raised route stands out only by the others fading.
- On every map a check-in's place behaves like a Moment's: listed, counted, filtered by
  year, month or trip, kept beside a raised trip, and read together by time.
- The app's trip playback follows `packages/trip-map/src/player`, including routes that
  keep moving during playback.
- A trip reads as one conversation: the site's trip sidebar (`TripChat.astro`, see
  [DESIGN.md](apps/web/DESIGN.md)) mirrors the app's chat timeline, and the calendar's day
  list uses the same lines; change them together. App-only: timelines draw the way between
  stops (the trip's non-driven leg, else the record's `reachedBy`, else footprints while
  足迹 is on, else by road), computed on the device.

### Planned trips

- A planned trip is an ordinary trip with its own dates, places and records, public by
  default and shown on the site. Only a hand-set `planned` flag distinguishes it, never
  the date.
- Records can also be `planned` (by hand or from a plan). They appear wherever records do
  and are only drawn differently. When the plan becomes the trip, its planned records stay
  planned until each is deleted or made real.
- The owner can mark a planned record or check-in `locked` on its page or in its editor.
  AI planning never changes a locked record; the owner still can. Making it a real record
  clears the lock.

## Website work

- Read [DESIGN.md](apps/web/DESIGN.md) before changing UI. Use Astro components, scoped
  plain CSS and the tokens in `apps/web/src/styles/index.css`. Keep the text-first design,
  system dark mode and reduced-motion behavior. The app has its own design system (see its
  guide).
- Map optimizations must not cost responsiveness or label detail; saving bandwidth doesn't
  justify slower zooming, panning or playback. Compare cold- and warm-cache motion with the
  previous version.

## Checks

- `vp run dev` and `vp run build` are the site's dev server and production build; the
  `dev`, `build` and `check` scripts differ from Vite+'s built-ins.
- Website, package or API changes: `vp check` and `vp test`, plus `vp run check` (Astro
  diagnostics), and `vp run build` when site behavior or build inputs change.
- trip-map bundle changes: `vp pack` (its `build` script also builds the CSS).
- CI is `.github/workflows/verify.yml`. Run the Moment or Zeekr audits when changing their
  data.
- Android and the Mac app use the Gradle wrapper, not Vite+; run the checks in the Android
  guide when the app or its API contract changes. Apple apps: `swift test` in `apps/apple`
  when changing them or their code generators.
- Generators write every platform's file at once; never hand-edit a `Generated` file.
- Docs-only changes need formatting and link/import checks only.

## Repository skills

Skills live in `.agents/skills`; `.claude/skills` holds a relative symlink to each. Add new
skills there and link them. Read a skill only when the task needs it.

- [create-moment](.agents/skills/create-moment/SKILL.md): record a Moment from the user's
  exact text and media.
- [zeekr-data](.agents/skills/zeekr-data/SKILL.md): capture and validate car data from the
  phone.

<!--VITE PLUS START-->

# Using Vite+, the Unified Toolchain for the Web

This project is using Vite+, a unified toolchain built on top of Vite, Rolldown, Vitest, tsdown, Oxlint, Oxfmt, and Vite Task. Vite+ wraps runtime management, package management, and frontend tooling in a single global CLI called `vp`. Vite+ is distinct from Vite, and it invokes Vite through `vp dev` and `vp build`. Run `vp help` to print a list of commands and `vp <command> --help` for information about a specific command.

Docs are local at `node_modules/vite-plus/docs` or online at https://viteplus.dev/guide/.

## Built-in Commands vs Scripts

`vp <name>` runs a built-in command. `vp run <name>` runs a `package.json` script or a `vite.config.ts` task. Scripts cannot overwrite built-ins, so `vp dev` and `vp run dev` may do different things. Check `package.json` and `vite.config.ts` first, and run `vp run <name>` when the project defines a script or task with that name.

## Running Scripts

Run TypeScript and JavaScript files with `vpx <file>`, for example `vpx ./scripts/seed.ts --dry-run`. It handles enums, decorators, JSX, and tsconfig `paths`, so do not add `tsx` or `ts-node` to run scripts. `vpx --watch <file>` reruns the file when a file it uses changes, in place of `tsx watch`. `vp node <file>` runs plain Node.js.

## Tool Versions

Run `vp toolchain` to show versions and relationships in the active Vite+
release. Add a tool name to select part of the graph. For example, run
`vp toolchain vite`. Use `--global` to ignore the local `vite-plus` package. Use
`vp why <package>` to show the package-manager dependency graph.

## Review Checklist

- [ ] Run `vp install` after pulling remote changes and before getting started.
- [ ] Run `vp check` and `vp test` to format, lint, type check and test changes.
- [ ] Check if there are `vite.config.ts` tasks or `package.json` scripts necessary for validation, run via `vp run <script>`.
- [ ] If setup, runtime, or package-manager behavior looks wrong, run `vp env doctor` and include its output when asking for help.

<!--VITE PLUS END-->
