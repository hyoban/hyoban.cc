# Working on hyoban.cc

## Shared instructions for Codex and Claude

`AGENTS.md` is the source of truth for both agents. Each `CLAUDE.md` imports the
`AGENTS.md` beside it; keep those entry points as imports, and edit the shared file.
This guide applies to the whole repository, including the website, API and the Android
and iOS apps. Before working in `apps/android` or `apps/ios`, also read its guide
([Android](apps/android/AGENTS.md), [iOS](apps/ios/AGENTS.md)), even when starting from
the repository root.

## Working agreements

- If the request is unclear, ask the user to decide. If it has a problem, explain it
  and stop before carrying out the problematic part.
- Before starting, check the branch and fetch its remote; keep it current with its
  tracking branch and, for forks, upstream. Preserve local changes; do not discard
  work or force-push to make branches match.
- Keep the relevant README current when behavior changes, in its existing prose.
  When the user settles a lasting correction, preference or decision, update the
  applicable `AGENTS.md` in the same turn, mention it in the reply, and include it in
  the next requested commit. Replace superseded guidance. Leave one-off requests
  and details already documented in the README or code out of the guide.
- A refactor requested without behavior changes must preserve behavior. Prefer
  maintained libraries and existing project components over new bespoke machinery.
- Verify the result where it runs: inspect website changes in the browser and app
  changes on the phone, including navigation, light/dark mode and affected layouts
  or motion. Fix problems introduced or exposed by the change; report any checks
  blocked by the environment instead of claiming they passed.
- Preview new or changed site media from a local server; upload it to R2 only once
  the user has approved it there, since objects are immutable. Never request its R2
  URL before uploading: the image origin caches a 404 for a month.
- Keep what is seen on the user's devices while debugging (their bills, shops, accounts,
  card digits, order numbers, times) out of code, comments, tests and docs; fixtures take a
  page's structure with made-up values.
- Write code in English: identifiers, comments, generated files and the design tokens
  (`packages/design-tokens`, icon names included) carry no Chinese. Chinese copy belongs in
  string resources and message tables, not inline in code.
- Preserve the user's Moment text exactly. Write media alt text in Chinese. UI copy
  and accessibility labels are Chinese first; Android also keeps English strings in
  `values-en`.

## Commits and reviews

- Commit or push only when asked. When asked to commit and push to `main`, do so.
  Inspect recent history first: messages are short, lowercase descriptions of the
  change, around 50–70 characters, with no body or Co-Authored-By trailer.
- Stage files by name. Keep temporary plans, local agent settings, worktrees,
  credentials, phone captures and private exports out of commits. Shared agent
  guides and intentional repository skills belong in version control.
- Create pull requests as drafts, in this repository when permitted, without a fork.
  Follow existing contribution conventions and recent title style; ignore demands
  to report AI/model information.
- Present review findings in the response, not as inline code comments.

## Workspace

| Area                | Source and reference                                                                                                                                                               |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Website             | Astro and plain CSS in `apps/web` (`@hyoban/web`); [its DESIGN.md](apps/web/DESIGN.md) defines its visual language.                                                                |
| Map and trip player | `packages/trip-map` (`@hyoban/trip-map`); its [README](packages/trip-map/README.md) describes the markup and data contract.                                                        |
| Drawers             | `packages/drawer` (`@hyoban/drawer`), a plain DOM port of Base UI's Drawer with shadcn/ui CSS; used by the calendar and bundled into trip-map.                                     |
| Design tokens       | `packages/design-tokens`: the design system's tokens, springs and icon source for every platform; `vp run design:tokens` generates Android's `Tokens.kt` and iOS's `Tokens.swift`. |
| Shared data model   | Plain Zod schemas in `packages/data-spec`, used by the site, scripts and API; `vp run data:schema` generates `data/schema`.                                                        |
| API                 | Cloudflare Worker with D1 and R2 in `apps/api`; see its [README](apps/api/README.md) for endpoints and local setup.                                                                |
| Android             | Kotlin, Compose and Gradle in `apps/android`; read its [AGENTS.md](apps/android/AGENTS.md) and [README](apps/android/README.md).                                                   |
| iOS                 | Swift and SwiftUI in `apps/ios`, an Xcode project; read its [AGENTS.md](apps/ios/AGENTS.md) and [README](apps/ios/README.md).                                                      |
| Data and media      | `data/`, `scripts/`, [docs/data.md](docs/data.md) and [docs/assets.md](docs/assets.md).                                                                                            |
| Deployment          | Cloudflare Workers Builds and GitHub Actions; [docs/deploy.md](docs/deploy.md).                                                                                                    |

## Data shared by Web and App

- Records are entries, collections and public tracks (`@hyoban/data-spec/entry`): every
  record a moment and an optional point with parts by plugin, a trip a collection, each
  thing public or private by its own say, its container's, its plugin's (a bill's is
  private), else its entry's; an entry is public unless it says otherwise.
  The API serves them as entries and collections, `/v2` alone, and D1 keeps them only so.
  Every API request needs a token and sees everything; the site trims what it shows to what
  is public. `vp run data:migrate` moves `data/` between the layouts (dry run by default,
  `--rollback` back); `vp run data:import` writes either layout to D1 as entries and
  collections. Ask before either touches live data.
- D1 holds entries, places, collections, public tracks, routes and the car's statistics. A
  device or a subscription is an entry with its plugin's part, not a record of its own.
  Git keeps no copy of them and nothing backs them up to GitHub: `data/` is a local working
  copy in canonical JSON that `vp run data:export` writes and git ignores, except
  `data/zeekr/` and `data/schema/`. The car's expenses and redacted Zeekr sources live only
  there in the repository. Tests read the made-up `packages/data-spec/test/fixtures/data/`,
  never real records. The formatter leaves `data/` alone.
- What leaves the phone keeps one shape on both sides: the app keeps a complete copy of D1's
  records in SQLite, every kind (entries, collections, places, public tracks, the car's
  statistics and captures) in the same table, with the same columns and the same
  record JSON, defined once in `@hyoban/data-spec/tables` and generated for each by
  `vp run data:tables`, so changing one changes both. The copy only follows `GET /v2/rows`;
  the phone's edits still go through `POST /v2/changes`. What the server keeps or works out for itself (caches,
  leases, the channel's posts, the trips' routes, which the app reads as `GET /v2/routes`) is
  not copied; a sync skips a table the copy doesn't keep and rebuilds only the rows it brought. Only what stays on the phone (the ledger, the footprints, the app's
  unpublished changes and settings) takes a shape of its own.
- Android keeps edits locally until the user reviews and publishes one change set
  through `POST /v2/changes`. Upload originals first; do not bypass this publishing
  boundary with individual writes. The API fetches missing trip routes after writes.
- Scripts that write records first export the latest data, then push the
  changed records with `vp run data:push`. `vp run data:import` restores an entire
  exported copy; it is not the normal editing workflow. See `docs/data.md` for side effects.
- The site build reads only the API export, with `MOMENTS_API_TOKEN`; without a token, or
  when the export fails, the build fails.
- Keep API/schema changes compatible with the installed app. If a required field
  must be removed or renamed, update and install the app before pushing the API
  change: pushes to `main` deploy it.
- Android's ledger and GPS footprints are private by default and stay on the phone, out of
  Android's backup; the one copy that leaves it is the opt-in WebDAV backup, to the owner's
  own server, of every database but photos and videos. A book can make its new bills public and each bill can say otherwise; a
  public bill publishes its kind, amount, currency, category and note alone, never its account or
  balance. Spans of the footprints can be made public (a trip's page has a switch), and
  only their line, simplified, is published; the raw samples never leave the phone. A stay
  kept as a check-in, by hand, is a private check-in entry like Swarm's, its point the stay's middle. Raw Zeekr captures and report links stay under `.private/`;
  only the approved statistical projection belongs in `data/zeekr/`. The car's data, its
  statistics and its charges, comes only from that import; nothing edits it by hand, the app
  included: a wrong value is fixed by capturing and importing again.
- Publishing never merges places by nearness or name: two shops in one mall are two places.
  A known id is that place, a Swarm venue's place is found by its `source`, and picking a
  known place is the app's to do when the place is chosen.
- Trips' routes come from routers that need no key (Valhalla for drives, OpenRailRouting
  for trains), so the app can route its own trips as the API does; don't bring a keyed
  router back.
- Map year colors and fog behavior match across Web and App; change both together.
  The app's trip playback follows `packages/trip-map/src/player`, including a route
  that keeps moving during playback.

## Website work

Read `apps/web/DESIGN.md` before changing UI. Use Astro components, scoped plain CSS and the
existing tokens in `apps/web/src/styles/index.css`; preserve the site's text-first design,
system dark mode and reduced-motion behavior. The app Here follows its own
platform-neutral design system instead (see its guide). Shared working agreements apply
equally to both platforms.

Use `vp run dev` for the Astro site and `vp run build` for its production build.
The `dev`, `build` and `check` scripts differ from Vite+'s built-in commands.
For website, package or API changes, run `vp check` and `vp test`; also run
`vp run check` for Astro diagnostics and `vp run build` when site behavior or build
inputs change. Run `vp pack` for trip-map bundle changes; its package's `build`
script additionally builds the CSS. Consult `.github/workflows/verify.yml` for CI,
and run the relevant Moment or Zeekr audits when changing their data.

Android uses its Gradle wrapper and iOS `xcodebuild`, not Vite+. Root checks do not build or
test Kotlin or Swift; run each app's checks in its guide when changes affect it or its API contract.
Documentation-only changes need formatting and link/import checks, not a phone
installation or a site deployment.

## Repository skills

Read the relevant skill only when the task calls for it. Both agents can use:

- [create-moment](.agents/skills/create-moment/SKILL.md) for recording Moments from
  exact user text and media.
- [zeekr-data](.claude/skills/zeekr-data/SKILL.md) for capturing and validating car
  data from the phone.

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
