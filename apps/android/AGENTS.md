# Working on the Android app

These rules extend the [shared guide](../../AGENTS.md) for `apps/android`, for Codex and
Claude alike. Keep lasting Android rules here and product facts in [README.md](README.md).

Run commands from `apps/android` with JDK 25+ and the Android SDK set in `local.properties`
(`sdk.dir=…`).

## Modules

- `:app` is the phone, `:desktop` the Mac (Compose Desktop), `:shared` (Kotlin Multiplatform,
  Android + JVM) what both run. The Mac app is to do what the phone does, so code moves from
  `:app` into `:shared` one part at a time, keeping its package and the phone's behavior;
  each step builds and tests `:app`. `:desktop` follows the desktop boards (D). The SwiftUI Mac
  app in `apps/apple` shares no code with it.
- Shared code may use the JDK and JVM libraries (OkHttp, `java.time`), but never `android.*`,
  `R`, `Context`, `BuildConfig` or Android-only libraries (Media3, Glance, WorkManager,
  Capsule). Platform differences are `expect` in `commonMain` with `actual`s in `androidMain`
  and `jvmMain`, the phone's keeping what it already did. Android code that shared code calls
  (`Photos`, `PhotoRef`) is an `actual` in `androidMain`, taking the context from `AndroidApp`
  when the caller has none. Small differences (language, back, keyboard, dialog windows) are
  `expect` functions in `ui/components/Platform.kt`.
- Maps use MapLibre Compose on both: OpenGL on the phone, Metal on the Mac in a presentation
  host from `Main.kt`. Don't add the classic Android SDK back.
- Share first. The Mac's version of anything goes in `:shared`: a desktop layout is a branch on
  `LocalDesktop` or a shared component (`ui/design/Desktop.kt`: `Toolbar`, `DesktopDialog`).
  `:desktop` keeps only its windows (`Main.kt`), container and `DesktopDevice`.
- A declaration `:app` uses from `:shared` is public; its tests stay in `:app` until they move.
  Hold strings as `StringResource`, never `Int`. Generators write into `:shared` (`Tokens.kt`,
  `AppIcons.kt`, `CopyTables.kt`, `Modules.kt`). Paths below are packages, in whichever module
  holds them.

### Containers and devices

- `AppContainer` (`:shared`) holds what every device shares: settings, records, the copy and
  publishing. `PhoneContainer` and `DesktopContainer` extend it. A screen takes `AppContainer`
  unless it needs the phone's own (writable ledger and footprint stores, widgets).
- What the base needs of a device that no module owns (place search, cities, publishing work)
  is an abstract or open container member, never a `Context`. Settings go through `Prefs`
  (shared preferences on the phone, a JSON file on the Mac).
- Shared pages ask for device-specific behavior through `LocalDevice` (`ui/Device.kt`): a
  Moment's menu, sharing, picking photos, location, pre-publish questions, windows, viewer
  motion. Only device differences belong there; a plugin's needs go through a point, never
  `Device`, and a module's own pages use a local of their own (`LocalLedgerDevice`).
- A shared page that needs something only the phone has takes it through `Device` or a
  plugin service's read-only interface (`LedgerSource`, `Footprints`) rather than staying in
  `:app`.
- On the Mac and tablets, the ledger and footprints are read from the phone's latest WebDAV
  backup (`backup/PhoneBackup`) and are read-only (`LedgerSource.writable` false; boards
  D17–D20, T17–T20). Pages gate every write on `writable`.
- 相册, 选照片 (`ui/picker`) and the NAS client (`nas/`) are shared. Photo sources are
  plugins (`Points.photoSources`, by `order`): the NAS on every device, gallery and camera in
  `:app`. A device adds only sharing and storage through `Device.picker` (`PickerDevice`). The
  Mac picks pictures from the file dialog, not videos yet.

### Mac shell

`Main.kt` follows the design's Sidebar card: no title bar of its own (content under a
transparent one, window buttons over the sidebar, which still drags), a 232-wide sidebar (64,
icons only, under 1024), and the current section's page beside it. `SubPage` and `TabPage`
draw the 52 toolbar instead of floating circles. Editors are `DesktopDialog`s, a bill or
place opens in the inspector, a bottom sheet becomes the inspector, 设置 is its own window
(`SettingsPanes`), and 初次打开 a small window. Esc and ⌘[ go to pages' `BackHandler`s first
(`DesktopBack`) so drafts are confirmed; pages take keys with `OnDesktopKey`.

Check Mac changes in its window (`./gradlew :desktop:run`), light and dark.

### Plugins

- Every feature reaches the pages through plugins (`plugins/`). A `Plugin` adds to points
  (`Point`) in `contribute()` and provides services (`Service`). Host pages read points from
  `Plugins.registry` and never name a plugin; the host's navigation names no plugin's page.
- Points live in `Points` (`:shared`), `PhonePoints` (`:app`: rows on 我, pages, side-key
  actions, heatmap sources, background work, `PhoneNeed`, `PhoneChannel`) and `DesktopPoints`
  (`:desktop`: sidebar sections and pages). Add a point only once two plugins contribute to it.
- A module's stores and clients are its plugin's services, keyed in `Services`: `ledger`,
  `footprints`, `footprintRecording` (phone only), `nas`, `swarm`, `noteSearch` (phone only),
  `shownCheckins`. Each is built once per container on first request. Its own plugin, and
  anything that must work with the module off, uses `registry.own`; everything else uses
  `registry.service`, which is null while the module is off. Each has an extension property
  (`container.ledger`) so the host never names the plugin. The place search stays the
  container's.
- A plugin with a `module` follows that module's switch (`registry.on`). A plugin without one
  may have a per-device switch (`switchKey`, `Settings.isPluginOn`), e.g. the NAS's and camera's
  在相册里显示. Points read on or off for layout use `contributions` and `shownWith`. Only a
  module's own pages, stores, services and the containers wiring them read its switch.
- Device features are phone plugins without a module (gallery, 相机, 侧键, 准备图文 albums,
  WebDAV backup, updates; 自动记账 belongs to the ledger). Each returns null for its row when
  the phone lacks what it needs, so 我 checks nothing itself. A settings group is shown by its
  module alone, never by the plugin's own switch.
- A page of a plugin without a module (`XiaohongshuPlugin`'s `NoteSignIn`) is still owned
  through `Points.pages` and registered through `PhonePoints.pages`.
- `DayRecord` and `DayAnchor` draw themselves; host pages lay them out by time and never name
  a module. Shared by every page: the movement contract (`Footprints`, `TrackPoint`,
  `Services.dayTracks`) and money formatting (`data.money`).
- `Plugins.shared` are every device's; the phone and Mac add theirs with `Plugins.install`
  (from `MomentsApplication` and `main`, and in tests that read phone points). Registry order
  is display order unless items carry an `order`.
- `suggest_plan` belongs to the planner; its lookups are plugins': `find_place` and
  `route_time` (`PlanningPlugin`, every device) and `search_notes` (`XiaohongshuPlugin`, phone,
  only while signed in).
- Notification channels: a channel's id, name and importance never change once shipped
  (`NotificationChannelsTest`). Create each just before its first notification, never all at
  start. The host's own channels are `HostChannels`.
- Files in `noBackupFilesDir` are declared in `Points.kept`. Ledger and footprint databases,
  the footprints' two JSON files and plan chats go into the WebDAV backup (`BackupArchive`),
  in plugin order with `copy.db` last; secrets, caches and pairing are declared as excluded.
  `BackupTest` fails for an undeclared file.

## On the phone

- The phone is the user's, with real data and a live link to the site. After a runtime or UI
  change, build and install the debug build without asking:
  `./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  Build only what the step needs.
- The user tests it. Don't launch, open, tap or publish yourself; say what to try and what to
  look for, and read logcat (crash buffer, `Moments:W`) when asked. For colour, layout or
  motion changes, ask the user to check dark mode, landscape or dropped frames.
- Run a phone test only when asked, and only the steps named, with
  `scripts/ui.py open|dump|tap|back`, `moments://` links and screenshots. Screenshot before
  each tap by position, never tap blind, chain nothing after a step that may have failed, and
  stop when anything unexpected happens. Menus and dialogs are separate windows where
  `ui.py` can't see test tags: tap their items by text.
- The debug build is debuggable (the owner's choice): read app files with
  `adb exec-out run-as cc.hyoban.moments cat <file>` (plan chats: `no_backup/plan-chats.json`)
  rather than scrolling, and only to answer what the owner asked. It runs several times
  slower, so judge speed and motion on `-Pfast` or the release.
- Install the release only when asked, not even before pushing:
  `./gradlew :app:assembleRelease`, then `adb install-multiple -r
  app/build/outputs/apk/release/app-release.apk
  app/build/outputs/apk/release/baselineProfiles/0/app-release.dm` (with the profile the phone
  compiles ahead of time; `dumpsys package` says `speed-profile`). Debug and release share a
  signing key, so either installs over the other. The emulator only gets debug builds.
- Release (README, Releasing) only when asked. Check Mac packaging changes in the packaged
  build (`./gradlew :desktop:packageReleaseDmg`), opening each page by `HERE_OPEN` and reading
  stderr. Keep anything reached by reflection, JNI or ServiceLoader in `desktop/rules.pro`. The
  release key and passwords are the user's: never create, read, print or commit them, or set
  repository secrets.

### Never lose the app's data

The ledger and footprints live in `noBackupFilesDir` with no other copy. Ask first, saying
what would be lost, before `adb uninstall`, `pm clear`, reinstalling after
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` (fix signing instead), downgrading (`install -d`),
deleting or overwriting app files, or running a new database migration on the phone before it
passed on a copy.

### adb

- The phone is usually on wireless adb. Take its serial from `adb devices`, skipping
  `emulator-*`, and never start the emulator while the phone is connected. If it is locked or
  adb dropped, say so instead of working around it.
- If `adb devices` is empty, restart the server (`adb kill-server`, `adb start-server`); the
  connect service often appears in `adb mdns services`. If only the pairing service shows,
  scan that host's ports 30000–50000 for the other open one and `adb connect` (it's already
  paired). On "No route to host", restart and retry.
- Publishing, once the user agrees, is tested with a Moment written on the phone with text
  "test" and 不加地点 (so no real place reaches the site), published, deleted, and the deletion
  published. Close the keyboard before tapping the place row, or the tap types into the text.
- A failed publish shows only a brief toast; the exception is in `adb logcat -s Moments:W`,
  the API side in `npx wrangler tail hyoban-cc-api` (read-only, from `apps/api`). Phone traffic
  goes through FlClash, which drops and stalls at times; uploads must ride that out.

### Before committing

- Run what CI runs (`.github/workflows/verify.yml`):
  `./gradlew :app:testDebugUnitTest :app:assembleDebug :desktop:classes --no-daemon`.
  Docs-only changes use the shared guide's docs checks.
- Afterwards, close any emulator you started and stop this task's idle Gradle/Kotlin daemons:
  check `./gradlew --status` first, use `adb -s <serial> emu kill` and `./gradlew --stop` only
  when every affected daemon is idle and yours, and confirm they exited. Never close the user's
  IDE or interrupt unrelated processes or builds.

## Design

- The app follows its own design system, the 「Here App 设计系统」 artifact, not Material 3.
  It is platform-neutral (for future iOS, Android and web versions): name things by what they
  are, not by Compose or Material, and record only where Android implements it. Desktops keep
  its look with a desktop structure; tablets stay touch (D and Pad boards, Desktop and Pad
  cards). The design rules themselves live in the artifact's README and component cards; this
  section covers only the Android implementation.
- Design first. A change to what the app shows or does goes into the design first: page
  artboards and notes in the 「Here App 页面设计稿」 canvas, component cards (or a new card) in
  the design system. If building shows the design is wrong, fix the design in the same task.
  When a task ends, the artboards, cards and app agree.
- No Material dependency (`androidx.compose.material3`, `glance-material3`) and no icon
  packs. Components are the app's own in `ui/design` on Compose foundation: `AppTheme` (colour
  roles, type, shapes, springs, a press wash instead of ripples) and every component. Some keep
  Material's names. Keep only what is used: `Button` for the page's one main action,
  `TextButton` for the rest, one chip look (`FilterChip` to choose, `AssistChip` to act), no
  unused icons.
- Tokens come from `packages/design-tokens`: `vp run design:tokens` generates
  `ui/theme/Tokens.kt`; never edit it, change `tokens.json`, regenerate and publish to the
  artifact. Colours are `tokens.*` by the design's names, with no role layer. Hairlines are
  `cardBorder` and `dividerColor`, the one shadow `ShadowElevation` via `softShadow`, levels
  `SurfaceLevel`.
- Icons are `AppIcons` (24 grid, 2 stroke, round ends, softened corners), generated into
  `ui/design/AppIcons.kt` by `python3 packages/design-tokens/icons/kotlin.py` (from the repo
  root) from `icons/icons.py`. Draw new icons there first, in the same hand; never edit
  `AppIcons.kt` or use another icon library.
- Text uses the six styles by name (`titlePage`, `title`, `bodyReading`, `body`, `label`,
  `caption`), system sans with tabular figures. A heading is a style's `.strong` (600), never a
  bigger size. Timeline and trips are `MomentFeed(chat = true)`; a ledger day starts with
  `DayHeader`. Lazy-list groups use `groupedRow`.
- Spacing is `Space.xs` … `Space.xl`: `Space.l` within a section, `Space.xl` between groups
  and days. Large fonts are `isLargeFont()` (130%+), read in composition.
- No bars: `TopBar` floats `IconButton`s as circles (`LocalFloatingIconButtons`). Every
  `SubPage` and `TabPage` runs under its bar with `TopBarLargeTitle` in the page's layer, padding
  by `LocalTopClearance`; a control that stays under the circles is its `accessory`. Scroll
  edges use `edgeBlur` (Haze progressive blur over a `hazeSource` that draws the page colour),
  or blur only the text. `TitleMenu` switches between sibling pages; `subtitleAction` puts a
  control over the subtitle's figures. Floating controls over photos or maps are
  `FloatingButton`.
- Continuous corners are paths, clipped by a software mask every frame. Where content keeps
  clear of the corners, draw the shape behind it (`background(colour, shape)`, `sheetSurface`
  for sheets) instead of clipping. Clipping also hit-tests against the path, which drops taps
  deep in tall surfaces; if you must clip, clip only what is drawn, as `SegmentedGroup` does.
- Settings are declared, not drawn: a plugin's `SettingsGroup` returns a `SettingsGroupSpec` of
  `SettingsRow`s (switch, value, choice, action, page, external, status; radio and lines in a
  panel) drawn by `SettingsGroupView`; 我's rows by `SettingsRowsOnPage`. Anything else opens
  the plugin's own dialog from a value or action row; a new row kind goes into the
  SettingsGroup card first. Switches are `AppSwitch`.
- When the system confirms an action itself (a gallery photo to its bin), don't ask first too.
- Haptics are the design's roles from `rememberHaptics()` (`ui/design/Haptics.kt`), never
  `HapticFeedbackType`.
- Touch targets: a 40dp button keeps a 48dp target (`minimumInteractiveComponentSize`); a 36dp
  chip is laid out at its own size, so chip rows stand 8dp apart.
- Strings are Compose resources in `:shared` (`composeResources/values`, Chinese, and
  `values-en`), shared by phone and Mac, with TalkBack descriptions: `Res.string.*` from
  `cc.hyoban.moments.res`, via `stringResource` in composition and `textOf`/`pluralTextOf`
  outside it. Placeholders are only numbered `%1$s`/`%1$d`; format decimals in code
  (`decimals`) and write percent signs plainly. Only what Android XML names (manifest, widget
  and accessibility descriptions) stays in `:app`'s `res/values`.
- A page explains itself by layout, headings, icons and states, not by usage notes. If it needs
  one, fix the design. Notes are only for what the page can't show: costs, what leaves the
  phone, thresholds, other apps' limits. Don't put visible state into words (a dashed outline,
  a check, a lock, a switch's state, the title); give it to TalkBack (`stateDescription`).
- Follow what the app should be rather than the website, except where the shared guide asks
  them to match.

## Maps and motion

- What a map draws, and each layer's time, is chosen only in its layers menu
  (`MapLayersMenu`, `MapTime`). A new layer gets a row there, following every layer's time
  unless given its own; never a separate button, legend or panel switch. Footprints' time is
  chosen there too. 照片 is `MapPhotosPlugin`'s row at `LayerSlot.AFTER_ROUTES` for sources with
  `PhotoSources.placesPhotos`; places, routes and fog are the host's rows. Plugin layers are
  placed by `LayerSlot`, not registry order. `MapScreen` still draws photo heaps and their sheet.
- Year colours are the tokens' `YearColors`, matching `packages/trip-map/src/theme.ts`; the fog
  is `packages/trip-map/src/map/fog-layer.ts`. Ledger charts are a Compose `Canvas`
  (`ui/ledger/Charts.kt`), not a chart library.
- Full-screen interactive maps use a `Surface`; embedded non-interactive maps a `Texture`.
  Leave MapLibre's max frame rate unset so 90/120 Hz phones aren't capped at 60. Attach the
  `Surface` while the style loads and cover it with the load colour until the first frame;
  never draw a placeholder into it (EGL can't attach). Its EGL loop runs on its own looper.
- Keep the main atlas `MapState` alive at app/device scope across tabs, and after the first
  visit keep its presentation composed behind navigation (covered, non-interactive) rather
  than rebuilding the `Surface`. Preload the FFI library off main but don't prewarm its runtime
  at startup. Adjust base layers in the style document before loading, not after ready. Prepare
  record grouping, route distances and GeoJSON at container scope on a background dispatcher,
  used only while their source snapshots are current.
- Keep MapLibre's local-ideograph rasterizer on with the system sans; downloading CJK glyph
  ranges delays labels and fills the 50 MiB cache with fonts.
- Build the FFI fork's Android native code like the classic SDK: `-Oz`, full LTO, lld,
  `--icf=all`; its Rust image decoder `opt-level = "s"` with aborting panics. Measure as
  stripped, APK-compressed arm64 libraries and recheck first-map time on release before trading
  size for speed. The MapLibre forks come only as the pinned, hash-checked releases (README,
  Building).
- Animate map layers frame by frame with constant paint values and each layer's transition at
  0: data-driven values relayout a source a frame or more late, and MapLibre eases every
  change over 300 ms. Layers reset ahead of an animation get their own source.
- MapLibre silently drops filters or expressions it can't parse; check logcat for
  `Error setting filter` from `Mbgl`. Fog regions for the rest of the world all have id
  `outside`; tell them apart by `cx` and `cy`.
- After changing map layers, toggle `adb shell cmd uimode night yes`/`no` on the map: the style
  is rebuilt, and anything kept outside it must start over.
- The one shared element is a Moment's card growing into its page (`sharedCard`); other pages
  slide or fade, the photo viewer fades. It must not fight predictive back; check entering,
  leaving and in-between frames on the phone. Check animations frame by frame
  (`adb shell screenrecord`, frames extracted with ffmpeg), not from a few screenshots.

## Product rules

- The app works on the phone alone: the build has no API address or media origin (the API
  reports its own), and nothing requests records until an API and token are set in 同步与来源.
  Don't add a default API. Until then nothing says 未发布 or points to publishing.
- Nothing reaches the site until 全部发布 sends the reviewed changes as one change set to
  `POST /v2/changes`. Don't add writes that bypass it.
- 发布更改 shows every change field by field against the site's copy (`ui/publish/`
  `ChangesScreen.kt`, `EntryDiffs.kt`, `PartDiffs.kt`, and plugins' `PartDiff`). A new field or
  way to change one comes with its diff line in the same change, so nothing publishes unseen or
  shows 没有改动. `PartDiffsTest` fails for a spec part without one.
- The phone stores every record it writes or changes as the entry it will publish
  (`changedEntries`); Moments are derived from entries. Don't bring back a separate Moment store.
- Ledger and footprints follow the shared privacy rules and publish only through 全部发布
  (`data/store/Outside.kt`). Their only other requests: exchange rates on request, Photon naming
  a footprint point for a bill (`footprints/BillPlaces`, point only), the place picker naming a
  stay, and the owner's WebDAV backup (`backup/`). Private and unpublished are different words:
  私有 and 未发布, never 仅本机.
- The ledger replaces iCost, so iCost backups and exports must keep importing. Check importer
  changes against real files with
  `ICOST_BACKUP=<backup> ICOST_EXPORT=<xlsx> ./gradlew :app:testDebugUnitTest --tests '*LedgerTest*'`
  (with both, it also checks merging a backup over an export-imported ledger); never commit them.
- Payment categorization matches expense category names from every book in the note before
  broad keyword rules; past bills don't affect it. A matched category also picks its book.
- Test ledger initialization with an isolated database, including entry before loading and
  concurrent first entries. Opening a test form must not add books to the user's ledger.
- Sharing hands off and leaves posting to the user: never post, pick a recipient or drive
  another app. The share assistant only fills composers and stops before sending; 自动记账 only
  reads payment pages, never tapping, typing or keeping them.
- Debugging the accessibility service: `adb logcat -s AutoBill:I` says whether a page was
  offered or already recorded. Amounts are what the page reads out (Alipay: 支出12.5元 for
  -12.50), so take fixtures from `uiautomator dump`. Read service state with
  `adb shell dumpsys activity service cc.hyoban.moments/.share.TelegramShareService`, not
  `uiautomator dump`, which interrupts it. Threads shows a single photo outside any
  `IgLazyRow`, and repeated shares stack photos into an open composer. Instagram's caption is
  missing from dumps but reaches the service; its view ids are full names
  (`com.instagram.android:id/…`), and an empty caption reads its hint as text.
- 演示模式 (`Settings.demo`) hides private things everywhere, widgets included: screens read
  `container.shownMoments`, `shownTrips` and `shownCheckins`, never the repository directly.
- Trip discovery uses check-ins at the places chosen as 家, leaving and returning, never a
  guessed home. It uses general distance and time thresholds, never exceptions for an export,
  city or date. Test with made-up records; private exports are local regression checks only.
- 相机 is for the owner's Sony a7C II only; don't widen it without asking. Write its protocol
  from documented facts (README, Camera), never copied from GPL code such as alpha-gps
  (permissive projects may be read). Test it only when asked and only the steps named.
- 侧键 is for the owner's phone model only (`SideKeys.supported`); don't widen it without
  asking. Adding an action must not assign or replace the user's bindings.
- The NAS photo API is undocumented (README, NAS): learn changes from the NAS's web bundle
  (`/photo/assets/`) or its traffic, and test deletes only when asked.
- 打码 uses ML Kit through Play services (the owner's choice) for faces and plates via Chinese
  text recognition. Play services fetches models and ML Kit reports usage to Google, the only
  Google request beyond location; photos never leave the phone. Ask before using ML Kit for
  anything else.
- 和 AI 规划 sends, only when asked and straight from the device to the owner's
  OpenAI-compatible service: the conversation, 补充说明 (after the app's instructions), the
  plan's name and days, every record in the plan (private ones too), and the device's time and
  zone; nothing else of the owner's places. Suggestions apply one at a time by the owner's
  tap (adding planned Moments, or changing or deleting planned Moments and future check-ins,
  never past or `locked` records), and publish only through 全部发布. Don't send more or apply
  without the tap.
  - `find_place` and `route_time` send only what the model names. Ways in the sheet and each
    day's driving ask the keyless routers by coordinates alone; when unreachable, they and
    `route_time` fall back to AMap or Tencent with the owner's keys (in China, never for
    trains), within each key's rate.
  - `search_notes` (phone only, after the owner signs in to Xiaohongshu in 同步与来源, at the
    owner's own risk to their account) reads search results in a hidden WebView: no taps,
    typing or posting, a few pages per search, at most one per 1.5 seconds. Its session stays
    out of Android's backups.
  - Conversations stay on the device and in its WebDAV backup (`plan-chats.json`, declared by
    `AiPlanPlugin` in `Points.kept`), nowhere else.
- Beyond the services above, the app's only request of its own is for updates: this repository's
  GitHub releases feed (not the API) and the APK (`update/`), carrying nothing from the phone.
- No keys in the build (README, Keys and services): keyed services take the owner's own keys,
  typed into 同步与来源 and stored beside the API token.
- APK size matters: the release is arm64 only; weigh what each new dependency costs. Pin a newer
  library version only for a reason, given in its comment in `gradle/libs.versions.toml`.
