# Working on the Android app

These instructions extend the repository's [shared guide](../../AGENTS.md) for
`apps/android`. They apply equally to Codex and Claude. Maintain durable Android
rules here and product behavior in [README.md](README.md).

Run the commands below from `apps/android`, with JDK 17 or newer and the Android
SDK configured in `local.properties` (`sdk.dir=…`).

## Check every change on the phone

- Iterate as fast as possible: after each runtime or UI change, build the debug build and
  install it over what is there, a few seconds each: `./gradlew :app:assembleDebug`, then
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Launch it and look at
  logcat's crash buffer. Build only what the step needs, not the CI set each time.
- The debug build runs slower, so judge motion and dropped frames on the release, and
  install the release once the change is done: `./gradlew :app:assembleRelease` (half a
  minute, R8's), then `app/build/outputs/apk/release/app-release.apk`. Both are signed
  with the debug key unless `local.properties` has a release key, so either installs over
  the other and the app's data stays.
- Testing never costs the app's data: the ledger and footprints live in `noBackupFilesDir`
  and have no other copy. Ask before anything that would lose or reset it, and say what
  would go: `adb uninstall`, `pm clear`, a reinstall after `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  (fix the signing instead), a downgrade (`install -d`), deleting or overwriting the app's
  files, or running a new database migration on the phone before it has passed on a copy.
- The phone is usually on wireless adb. Take its serial from `adb devices`, leaving out
  `emulator-*`, and never start the emulator while the phone is there. If the phone is
  locked or adb has dropped, say so instead of working around it.
- When `adb devices` is empty, restart the adb server (`adb kill-server`,
  `adb start-server`): the connect service often shows up in `adb mdns services` then.
  If only the phone's pairing service shows, scan that address's ports 30000–50000 for
  the other open one and `adb connect` to it (the phone is already paired); on "No route
  to host", restart the server and connect again.
- Publishing is tried with a test Moment written on the phone (text "test", 不加地点 so
  no place of the user's reaches the site), published, then deleted and the deletion
  published. The place row defaults to where the phone is: close the keyboard before
  tapping it, or the tap types into the text.
- A `DropdownMenu` or dialog is a window of its own, where `ui.py` can't see test tags:
  tap its items by their words.
- The new Moment page opens with the keyboard up, and `ui.py dump` still lists what is
  under it, so a tap there types instead: close the keyboard first.
- Look at it, don't just build it: `scripts/ui.py open|dump|tap|back`, the `moments://`
  links and screenshots. Check dark mode and landscape when a change touches colour or
  layout, and watch for dropped frames when it touches motion.
- A publish that fails says why only in a toast that soon goes; the exception is in
  `adb logcat -s Moments:W`, and the API's side in `npx wrangler tail hyoban-cc-api`
  (read-only) from `apps/api`. The phone's traffic goes through FlClash, whose link
  drops and stalls now and then, so uploads have to ride that out.
- Before committing run what CI runs (`.github/workflows/verify.yml`):
  `./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon`.
  Documentation-only changes use the shared guide's documentation checks.
- After builds and tests finish, close any emulator started for this task and clean up
  Gradle/Kotlin background processes used by this task once they are confirmed idle.
  Inspect `./gradlew --status` and the relevant processes first; use orderly shutdown
  (`adb -s <serial> emu kill`, and `./gradlew --stop` only when all affected daemons are
  idle and within this task's scope), then verify that the processes exited.
  Do not close the user's IDE, stop unrelated processes or interrupt another build.

- Programmable actions stay configurable: adding an action must not assign or replace the
  user’s gesture bindings.

## Design

- The app follows its own design system, the 「这里 App 设计系统」 artifact, not Material 3:
  the site's restraint on a phone. The design is platform-neutral, one design for the iOS,
  Android and web versions to come: keep Android-only conventions out of it (name things by
  what they are, not by Compose or Material), and record only where Android implements it. Its tokens come from `packages/design-tokens`, the one
  source for every platform: `vp run design:tokens` at the root generates `ui/theme/Tokens.kt`
  (colours, spacing, corners, shadows, opacities, type, springs), which is never edited by
  hand; change `tokens.json` there, regenerate, and publish it to the artifact. Text takes
  the design's six styles by their names (`titlePage`, `title`, `bodyReading`, `body`,
  `label`, `caption`), all in the system sans with tabular figures; a heading is a style
  `.strong` (600), never a size larger; the timeline and a trip are a conversation (`MomentFeed(chat = true)`: `DateCapsule`, Moments as `MomentCard(bubble = true)`, records in `RecordsBubble`), and a ledger's day opens with its header row (`DayHeader`). The app
  has no Material dependency (`androidx.compose.material3`, `glance-material3`): its
  components are its own, in `ui/design`, on Compose foundation. Don't add Material
  components or icon packs back.
- Icons are the design system's own set (`AppIcons`, 24 grid, 2 stroke, round ends and softened corners),
  generated into `ui/design/AppIcons.kt` by `python3 packages/design-tokens/icons/kotlin.py`
  (from the repository root) from `packages/design-tokens/icons/icons.py`. A new icon is drawn there first, in the same hand, then
  generated; never edit `AppIcons.kt` by hand or reach for another icon library.
- `ui/design` holds the theme (`AppTheme`: colour roles, type, shapes, springs, the press
  wash in place of a ripple) and every component: text and icons, surfaces, buttons, chips, the
  segmented control, fields, list rows, menus, dialogs, sheets, the top bar (no bar: `TopBar` floats its
  `IconButton`s as circles through `LocalFloatingIconButtons`), messages,
  pull-to-refresh and swipe-to-revert. Some keep Material's names.
  A list laid out row by row (a lazy list) draws its group with `groupedRow`.
- The design rules themselves (layers and hairlines, the accent and the ink, few cards, the
  tabs, the timeline, top bars, settings groups, touch sizes, words, share targets, the two
  palette exceptions, motion and back) are the artifact's README and component cards; read
  them there rather than here. What follows is only where Android carries them out.
- Colours are `tokens.*` by the design's names alone; there is no layer of roles over them.
  Layers: the hairline is `cardBorder` and `dividerColor`, the one shadow `ShadowElevation` drawn by
  `softShadow`, a surface's level `SurfaceLevel`.
- Keep only what is used: two buttons (`Button` for the page's one main action, `TextButton`
  for everything else), one chip look (`FilterChip` to choose, `AssistChip` to act, drawn the
  same), and no icon that nothing draws.
- Top bars switch between what the page is one of with `TitleMenu` and put a control over the
  subtitle's figures in `subtitleAction`. Settings are `SegmentedGroup` with `SwitchRow` and
  `ActionRow`, switches `AppSwitch`. What floats over a photo or the map is `FloatingButton`.
- A 40dp button keeps its 48dp target in the layout (`minimumInteractiveComponentSize`); a 36dp
  chip is laid out at its own size, so rows of chips stand 8dp apart, Compose widening its target.
- Spacing is five steps (`Space.xs` … `Space.xl`): things within a section are `Space.l` apart,
  groups and days `Space.xl`. Large system fonts are `isLargeFont()` (130% and up), so read it in composition.
- Words are in `values` (Chinese) and `values-en`, with TalkBack descriptions for what is shown.
- The map's year colours are the tokens' `YearColors`, the same as `packages/trip-map/src/theme.ts`, and the
  fog is the site's `packages/trip-map/src/map/fog-layer.ts`. The ledger's charts are drawn on a
  Compose `Canvas` (`ui/ledger/Charts.kt`), not with a chart library.
- Follow what the app should be rather than the web version, except where the shared guide
  asks it to match.

## Motion and navigation

- Shared elements must not fight predictive back, drag-to-dismiss or paging in the
  viewer; check entering and leaving, and the in-between frames, on the phone.
- Animate map layers a frame at a time with constant paint values, each layer's
  transition set to 0: MapLibre lays a source out again for a data-driven value, a
  frame or more late and drawing the default meanwhile, and eases every change over
  300 ms on its own. Layers set again ahead of an animation get a source of their own.
- Check an animation frame by frame: `adb shell screenrecord`, every frame out with
  ffmpeg, and measure each one, rather than judging from a few screenshots.
- After a change to the map's layers, switch light and dark on the map
  (`adb shell cmd uimode night yes`, then `no`): the style and its layers are built
  again, and anything kept outside them, or read before they're added, must start over.
- MapLibre drops a filter or expression it can't take without throwing, leaving the layer
  unfiltered; after setting one, look for `Error setting filter` from `Mbgl` in the app's
  logcat. The regions of the rest of the world in the fog's asset all have the id
  `outside`, so tell them apart by `cx` and `cy`, never by id.

## What the app is

- Nothing reaches the site until 全部发布到网站, after the changes waiting have been
  reviewed: one change set, like a commit, sent as entries to `POST /v2/changes` (the first
  shape to `/v1` on an API without it). Don't add writes that bypass it.
- The ledger and the footprints follow the shared guide's privacy rules and publish only
  through 全部发布到网站 (`data/store/Outside.kt`). Their only other requests are exchange
  rates, when asked, and Photon naming a bill's footprint point (`footprints/BillPlaces`),
  the point alone. Private and unpublished are two things, said 私有 and 未发布, never 仅本机.
- The ledger replaces iCost, so what iCost's export holds must keep importing:
  check a change to the importer against a real export with
  `ICOST_EXPORT=<xlsx> ./gradlew :app:testDebugUnitTest --tests '*LedgerTest*'`, and never
  commit an export.
- Automatic payment categorization matches expense category names across all books present
  in the note before falling back to broad keyword rules. Past bills do not affect it.
  A matched category also selects its owning book in the bill form.
- Verify ledger initialization with an isolated database, including entry before loading
  and concurrent first entries. Opening a test form must not add books to the user's ledger.
- Sharing hands off to the other app and leaves posting to the user. Never post, pick a
  recipient or drive another app automatically. The one exception is the opt-in share
  assistant (Telegram, Threads): an accessibility service that only fills the composer
  and stops before sending. The same service, while 自动记账 is on, only reads WeChat Pay's and
  Alipay's payment pages for a payment to offer as a bill: it never taps, types or keeps the
  page; `adb logcat -s AutoBill:I` says whether a page read was offered or already in the
  ledger. Its amounts are what the page reads out (Alipay's bill: 支出12.5元 for -12.50), not what it
  draws, so take fixtures from `uiautomator dump`. While debugging it, read its state with
  `adb shell dumpsys activity service cc.hyoban.moments/.share.TelegramShareService`,
  not `uiautomator dump`, which interrupts the service. Threads shows a single photo
  outside any `IgLazyRow`, and repeated shares stack photos into an open composer.
- No keys in the app: OpenFreeMap tiles drawn by MapLibre, Photon for place names.
  APK size matters, so the release is arm64 only; weigh what a new dependency costs.
- Pin a newer library version only for a reason, and say why in the comment in
  `gradle/libs.versions.toml`.

