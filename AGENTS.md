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

## Workspace

The map page and the trip player live in `packages/trip-map` (`@hyoban/trip-map`), a workspace package the site imports; its README describes the markup and data the client expects. The Moments, places and trips, and the trips' routes, which the API fetches itself after each write, live in the API's D1 database and are backed up as JSON records under `data/` by the data-backup workflow (`vp run data:export`), next to the use page's devices, all in the shape `packages/data-spec` (`@hyoban/data-spec`) defines with plain Zod, so the site, the scripts and a Worker can share it (the schema is also written out to `data/schema` by `vp run data:schema`); `src/data` and `src/moments` read them for the Astro pages and components. `apps/api` (`@hyoban/api`) is a Cloudflare Worker that serves those records from D1 to the site's build and the recording app; a script that writes records into `data/` itself pushes them back with `vp run data:push` (`vp run data:import` only restores the whole backup), its README lists the endpoints, and with `MOMENTS_API_TOKEN` set the site's build reads the API's export instead of `data/` (docs/data.md). The formatter leaves `data/` alone: records are written by the backup and the scripts in their own canonical JSON. Root `vp check` and `vp test` cover the packages and the API too; `vp pack` builds trip-map's publishable `dist`. The Android app that shows the Moments, places and trips from the API lives in `apps/android` (Gradle, Kotlin, Compose; see its README) and is built and tested with its own `./gradlew`, not by Vite+.
