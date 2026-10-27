# Working on hyoban.cc

## Shared instructions for Codex and Claude

`AGENTS.md` is the source of truth for both agents. Each `CLAUDE.md` imports the
`AGENTS.md` beside it; keep those entry points as imports, and edit the shared file.
This guide applies to the whole repository, including the website, API and Android
app. Before working in `apps/android`, also read [its guide](apps/android/AGENTS.md),
even when starting from the repository root.

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
- Preserve the user's Moment text exactly. UI copy and accessibility labels are
  Chinese first; Android also keeps English strings in `values-en`.

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

| Area                | Source and reference                                                                                                                           |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Website             | Astro and plain CSS in `src/`; [DESIGN.md](DESIGN.md) defines its visual language.                                                             |
| Map and trip player | `packages/trip-map` (`@hyoban/trip-map`); its [README](packages/trip-map/README.md) describes the markup and data contract.                    |
| Drawers             | `packages/drawer` (`@hyoban/drawer`), a plain DOM port of Base UI's Drawer with shadcn/ui CSS; used by the calendar and bundled into trip-map. |
| Shared data model   | Plain Zod schemas in `packages/data-spec`, used by the site, scripts and API; `vp run data:schema` generates `data/schema`.                    |
| API                 | Cloudflare Worker with D1 and R2 in `apps/api`; see its [README](apps/api/README.md) for endpoints and local setup.                            |
| Android             | Kotlin, Compose and Gradle in `apps/android`; read its [AGENTS.md](apps/android/AGENTS.md) and [README](apps/android/README.md).               |
| Data and media      | `data/`, `scripts/`, [docs/data.md](docs/data.md) and [docs/assets.md](docs/assets.md).                                                        |

## Data shared by Web and App

- D1 holds Moments, places, trips and routes. `data/` is their canonical JSON backup,
  refreshed by the data-backup workflow and `vp run data:export`. Devices and redacted
  Zeekr statistics live only in the repository. The formatter leaves `data/` alone.
- Android keeps edits locally until the user reviews and publishes one change set
  through `POST /v1/changes`. Upload originals first; do not bypass this publishing
  boundary with individual writes. The API fetches missing trip routes after writes.
- Scripts that write backup records first export the latest data, then push the
  changed records with `vp run data:push`. `vp run data:import` restores an entire
  backup; it is not the normal editing workflow. See `docs/data.md` for side effects.
- The site build uses the API export when `MOMENTS_API_TOKEN` is set, otherwise the
  committed backup. A failed authenticated export must fail the build.
- Keep API/schema changes compatible with the installed app. If a required field
  must be removed or renamed, update and install the app before pushing the API
  change: pushes to `main` deploy it.
- Android's ledger and GPS footprints stay on the phone, out of the API, website
  and Android backup. Raw Zeekr captures and report links stay under `.private/`;
  only the approved statistical projection belongs in `data/zeekr/`.
- Map year colors and fog behavior match across Web and App; change both together.
  The app's trip playback follows `packages/trip-map/src/player`, including a route
  that keeps moving during playback.

## Website work

Read `DESIGN.md` before changing UI. Use Astro components, scoped plain CSS and the
existing tokens in `src/styles/index.css`; preserve the site's text-first design,
system dark mode and reduced-motion behavior. Android follows its own Material 3
rules in its guide. Shared working agreements apply equally to both platforms.

Use `vp run dev` for the Astro site and `vp run build` for its production build.
The `dev`, `build` and `check` scripts differ from Vite+'s built-in commands.
For website, package or API changes, run `vp check` and `vp test`; also run
`vp run check` for Astro diagnostics and `vp run build` when site behavior or build
inputs change. Run `vp pack` for trip-map bundle changes; its package's `build`
script additionally builds the CSS. Consult `.github/workflows/verify.yml` for CI,
and run the relevant Moment or Zeekr audits when changing their data.

Android uses its Gradle wrapper, not Vite+. Root checks do not build or test Kotlin;
run the Android checks in its guide when changes affect the app or its API contract.
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
