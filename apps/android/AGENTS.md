# Working on the Android app

These instructions extend the repository's [shared guide](../../AGENTS.md) for
`apps/android`. They apply equally to Codex and Claude. Maintain durable Android
rules here and product behavior in [README.md](README.md).

Run the commands below from `apps/android`, with JDK 17 or newer and the Android
SDK configured in `local.properties` (`sdk.dir=…`).

## One Gradle project, two apps

- `:app` is the phone's app, `:desktop` the Mac's (Compose Desktop), and `:shared` (Kotlin
  Multiplatform, for Android and the desktop's JVM) what both run. The Mac app is to do what
  the phone's does, so code moves from `:app` into `:shared` a part at a time, keeping its
  package and leaving the phone's behaviour as it was; each step builds and tests `:app`.
  The Mac is not SwiftUI's: `apps/apple` is for iPhone and iPad.
- Shared code may use the JDK and JVM libraries (OkHttp, `java.time`): both targets are JVMs.
  What it may not touch is Android's own: `android.*`, `R`, `Context`, `BuildConfig`, and
  the Android-only libraries (MapLibre's Android SDK, Media3, Glance, WorkManager, Capsule).
  Where the platforms differ, `commonMain` declares it `expect` and `androidMain` and
  `jvmMain` each give theirs, the phone's being what it already did.
- `AppContainer` in `:shared` is what every device shares (the settings, the records, their copy
  and publishing); `PhoneContainer` in `:app` and `DesktopContainer` in `:desktop` are it with
  what the device has. A screen takes `AppContainer` unless it needs the phone's own (the ledger's
  and the footprints' stores, the widgets). Where the base needs something of the device, it is an
  abstract or open member the device's container gives, not a `Context`. Settings are kept
  through `Prefs`: the phone's shared preferences, a JSON file on the Mac.
- A declaration `:app` uses from `:shared` is public, and its tests stay in `:app` until they
  move too. A string resource is held as a `StringResource`, never an `Int`. The generators write into `:shared` (`Tokens.kt`, `AppIcons.kt`, `CopyTables.kt`,
  `Modules.kt`); the paths below are packages, in whichever module holds them.
- Check the Mac app where it runs: `./gradlew :desktop:run` opens its window; look at it,
  light and dark, before saying a change to it works.

## Check every change on the phone

- The phone is the user's, with their real data and a live link to the site. After a
  runtime or UI change, build the debug build and install it over what is there, without
  asking: `./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  The user tests it: don't launch, open, tap or publish on the phone yourself. Say what to
  try and what to look for, and read logcat (its crash buffer, `Moments:W`) when asked.
  Build only what the step needs, not the CI set each time.
- Only when the user asks for a test on the phone run one, and only the steps named: never
  tap blind, chain nothing after a step that may have failed, screenshot before each tap
  by position and tap only what it shows, and stop and say so when anything unexpected starts.
- Install only the debug build, never the release, unless the user asks for the release, not
  even before pushing. Then: `./gradlew :app:assembleRelease`
  (half a minute, R8's), then `adb install-multiple -r app/build/outputs/apk/release/app-release.apk
  app/build/outputs/apk/release/baselineProfiles/0/app-release.dm`: with its profile beside it the
  phone compiles the app ahead of time on install (`dumpsys package` says `speed-profile`), where
  the APK alone runs interpreted until the phone next compiles in the background. Judge motion
  and dropped frames on it then, as the debug build runs slower. Both are signed
  with the same key, the release key in `local.properties` or else the debug key, so either
  installs over the other and the app's data stays. The emulator only ever gets the debug
  build, so `run-as` can read its files.
- Releases go to GitHub by pushing an `android-v<x.y.z>` tag (README, Releasing), and only
  when the user asks for one. The release key and its passwords are the user's: never create,
  read, print or commit them, nor set the repository's secrets.
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
- Publishing, once the user has said yes to it, is tried with a test Moment written on the phone (text "test", 不加地点 so
  no place of the user's reaches the site), published, then deleted and the deletion
  published. A new Moment has no place unless a photo gives one; only its place picker,
  opened with none, locates the phone. Close the keyboard before tapping the place row,
  or the tap types into the text.
- A `DropdownMenu` or dialog is a window of its own, where `ui.py` can't see test tags:
  tap its items by their words.
- The new Moment page opens with the keyboard up, and `ui.py dump` still lists what is
  under it, so a tap there types instead: close the keyboard first.
- A test the user asks for uses `scripts/ui.py open|dump|tap|back`, the `moments://` links
  and screenshots. When a change touches colour, layout or motion, ask the user to check
  dark mode, landscape or dropped frames.
- A publish that fails says why only in a toast that soon goes; the exception is in
  `adb logcat -s Moments:W`, and the API's side in `npx wrangler tail hyoban-cc-api`
  (read-only) from `apps/api`. The phone's traffic goes through FlClash, whose link
  drops and stalls now and then, so uploads have to ride that out.
- Before committing run what CI runs (`.github/workflows/verify.yml`):
  `./gradlew :app:testDebugUnitTest :app:assembleDebug :desktop:classes --no-daemon`.
  Documentation-only changes use the shared guide's documentation checks.
- After builds and tests finish, close any emulator started for this task and clean up
  Gradle/Kotlin background processes used by this task once they are confirmed idle.
  Inspect `./gradlew --status` and the relevant processes first; use orderly shutdown
  (`adb -s <serial> emu kill`, and `./gradlew --stop` only when all affected daemons are
  idle and within this task's scope), then verify that the processes exited.
  Do not close the user's IDE, stop unrelated processes or interrupt another build.

- 相机 is for the owner's Sony a7C II alone; don't widen it to other bodies without asking.
  Its protocol code is written from documented facts (README, Camera), never copied from a
  GPL project such as alpha-gps; permissive ones may be read for reference. Test the camera
  only when the owner asks, and only the steps named: pairing, the location lock and the
  camera's Wi‑Fi act on a real camera.

- Programmable actions stay configurable: adding an action must not assign or replace the
  user’s gesture bindings. 侧键 is for the owner's one phone model (`SideKeys.supported`,
  README); don't show it on others or widen the match without asking.

## Design

- The app follows its own design system, the 「Here App 设计系统」 artifact, not Material 3:
  the site's restraint on a phone. The design is platform-neutral, one design for the iOS,
  Android and web versions to come: keep Android-only conventions out of it (name things by
  what they are, not by Compose or Material), and record only where Android implements it.
  Desktops (Mac, the web on a computer) keep its look but take a desktop's structure, a sidebar,
  toolbars, hover, context menus and shortcuts, as the page canvas's desktop boards (D, beside each
  page) and the design system's Desktop cards draw them. Tablets (iPad, Android tablets) stay touch,
  sized by the window, not the device: wider than 1024 a touch sidebar and list beside detail, 600–1023
  the sidebar as a rail and one pane, narrower the phone's layout; a side panel or a centred sheet in
  place of a bottom sheet, as the canvas's Pad boards (TV portrait, T landscape) and the design
  system's Pad cards draw them. Each canvas row reads phone portrait, phone landscape (H), Pad
  portrait, Pad landscape, desktop (D).
  Its tokens come from `packages/design-tokens`, the one
  source for every platform: `vp run design:tokens` at the root generates `ui/theme/Tokens.kt`
  (colours, spacing, corners, shadows, opacities, type, springs), which is never edited by
  hand; change `tokens.json` there, regenerate, and publish it to the artifact. Text takes
  the design's six styles by their names (`titlePage`, `title`, `bodyReading`, `body`,
  `label`, `caption`), all in the system sans with tabular figures; a heading is a style
  `.strong` (600), never a size larger; the timeline and a trip are a conversation (`MomentFeed(chat = true)`: `DateCapsule`, Moments as `MomentCard(bubble = true)`, records in `RecordsBubble`), and a ledger's day opens with its header row (`DayHeader`). The app
  has no Material dependency (`androidx.compose.material3`, `glance-material3`): its
  components are its own, in `ui/design`, on Compose foundation. Don't add Material
  components or icon packs back.
- Design first, and the two kept in step: a change to what the app shows or how it behaves
  goes into the design first, and the code follows it: a page's artboards and its note go in
  the 「Here App 页面设计稿」 canvas, which holds every page, and the component cards it uses,
  or a new card for a new part, go in the design system, which holds only components. Where
  building it shows the design wrong or not workable, change the design in the same task
  rather than leave the code to differ. Before a task ends, the artboards and cards it touched
  and the app say the same thing.
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
- The scroll edges (what passes under the status bar, the top bar's circles and the tab bar) are
  `edgeBlur`, Haze's progressive blur over a `hazeSource` that draws the page's colour itself, or
  the blur is only of the text, over the text. Every `SubPage` and `TabPage` runs up under its
  bar, the large title (`TopBarLargeTitle`) in the page's own layer, and pads its scroll at the
  top by `LocalTopClearance`; a control that stays under the circles with them (the ledger's
  segments) is its `accessory`.
- The continuous corners are paths, which the graphics pipeline draws and clips by a mask made in
  software every frame: `clip` to one masks every word and photo inside it. Where what a shape
  holds keeps clear of its corners, draw the shape behind it (`background(colour, shape)`) rather
  than clipping to it, and a large surface round at its top (a sheet) with `sheetSurface`.
- Top bars switch between what the page is one of with `TitleMenu` and put a control over the
  subtitle's figures in `subtitleAction`. Settings are `SegmentedGroup` with `SwitchRow` and
  `ActionRow`, switches `AppSwitch`. What floats over a photo or the map is `FloatingButton`.
- An action the system confirms itself (a gallery photo to its bin) goes straight to the
  system's prompt; the app doesn't ask first too.
- Haptics are the design's roles (lift, tick, armed/disarmed, toggle, key, confirm/reject) from
  `rememberHaptics()` in `ui/design/Haptics.kt`; call those, never `HapticFeedbackType` directly.
- A 40dp button keeps its 48dp target in the layout (`minimumInteractiveComponentSize`); a 36dp
  chip is laid out at its own size, so rows of chips stand 8dp apart, Compose widening its target.
- Spacing is five steps (`Space.xs` … `Space.xl`): things within a section are `Space.l` apart,
  groups and days `Space.xl`. Large system fonts are `isLargeFont()` (130% and up), so read it in composition.
- Words are Compose resources in `:shared` (`composeResources/values`, Chinese, and `values-en`),
  the same for the phone and the Mac, with TalkBack descriptions for what is shown: `Res.string.*`
  from `cc.hyoban.moments.res`, read by `stringResource` in composition and by `textOf` and
  `pluralTextOf` outside it (a notification, a widget, a store's message). A placeholder is
  `%1$s` or `%1$d`, numbered, and nothing else: a decimal is formatted in code (`decimals`) and a
  percent sign is written plainly. Only what Android's own XML names (the manifest, the widgets'
  and the accessibility service's descriptions) stays in `:app`'s `res/values`, as `R.string`.
- A page explains itself by its layout, headings, icons and states, not by notes or subtitles
  telling how to use it (what + does, that a tap opens it, what a check means); if it needs
  one, the design is wrong, so change the design. A note stays only for what the page can't
  show: what an action costs, what leaves the phone, a rule's threshold, another app's limits.
- The map's year colours are the tokens' `YearColors`, the same as `packages/trip-map/src/theme.ts`, and the
  fog is the site's `packages/trip-map/src/map/fog-layer.ts`. The ledger's charts are drawn on a
  Compose `Canvas` (`ui/ledger/Charts.kt`), not with a chart library.
- Follow what the app should be rather than the web version, except where the shared guide
  asks it to match.

## Motion and navigation

- The one shared element is a Moment's card growing into its page and shrinking back
  (`sharedCard`); other pages slide or fade through, and the photo viewer fades over the
  page. It must not fight predictive back; check entering and leaving, and the in-between
  frames, on the phone.
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

- The app is usable on the phone alone and is not tied to this repository's API: the build
  carries no API address or media origin (the API names its own), and nothing asks the network for records until
  both an API and its token are set in 同步与来源. Don't add a default API back. Until then the
  app is the phone's own: nothing says 未发布 or points to publishing.
- Nothing reaches the site until 全部发布到网站, after the changes waiting have been
  reviewed: one change set, like a commit, sent as entries to `POST /v2/changes`. Don't add
  writes that bypass it.
- The ledger and the footprints follow the shared guide's privacy rules and publish only
  through 全部发布到网站 (`data/store/Outside.kt`). Their only other requests are exchange
  rates, when asked, Photon naming a bill's footprint point or a stay being kept as a place
  (`footprints/BillPlaces`), the point alone, and the WebDAV backup the owner sets up (`backup/`). Private and unpublished are two things, said 私有 and 未发布, never 仅本机.
- The ledger replaces iCost, so what iCost's backup and export hold must keep importing:
  check a change to the importers against real ones with
  `ICOST_BACKUP=<backup> ICOST_EXPORT=<xlsx> ./gradlew :app:testDebugUnitTest --tests '*LedgerTest*'`
  (with both, it also checks that a backup merged over an export-imported ledger adds only
  what is newer), and never commit either.
- Automatic payment categorization matches expense category names across all books present
  in the note before falling back to broad keyword rules. Past bills do not affect it.
  A matched category also selects its owning book in the bill form.
- Verify ledger initialization with an isolated database, including entry before loading
  and concurrent first entries. Opening a test form must not add books to the user's ledger.
- Sharing hands off to the other app and leaves posting to the user. Never post, pick a
  recipient or drive another app automatically. The one exception is the opt-in share
  assistant (Telegram, Threads, Instagram): an accessibility service that only fills the composer
  and stops before sending. The same service, while 自动记账 is on, only reads WeChat Pay's and
  Alipay's payment pages for a payment to offer as a bill: it never taps, types or keeps the
  page; `adb logcat -s AutoBill:I` says whether a page read was offered or already in the
  ledger. Its amounts are what the page reads out (Alipay's bill: 支出12.5元 for -12.50), not what it
  draws, so take fixtures from `uiautomator dump`. While debugging it, read its state with
  `adb shell dumpsys activity service cc.hyoban.moments/.share.TelegramShareService`,
  not `uiautomator dump`, which interrupts the service. Threads shows a single photo
  outside any `IgLazyRow`, and repeated shares stack photos into an open composer.
  Instagram's caption is missing from `uiautomator dump` but reaches the service; its
  view ids are full names (`com.instagram.android:id/…`), and an empty caption reads out
  its hint as its text.
- The home screen widgets are the owner's own: they show private Moments and trips too,
  passing over only photos kept off covers. 演示模式 (`Settings.demo`, which took the place of
  hiding amounts) hides amounts, account names and private Moments and trips everywhere, the
  widgets included; screens read `container.shownMoments` and `shownTrips`, not the
  repository's, so a new screen keeps to it.
- Trip discovery uses general time and location evidence, never exceptions for a particular
  export, city or date. Cover its rules with made-up records; private exports are local
  regression checks, not fixtures.
- The paired camera is spoken to only over Bluetooth and its own Wi‑Fi (`camera/`): the
  location goes to the camera alone and its photos come straight to the gallery.
- The owner's UGREEN NAS (`nas/`), once set up in 同步与来源, is asked only from 选照片: UGREENlink
  for where it is, by its ID alone, and the NAS itself for its photo app's photos. Its API is the
  one its web app uses, undocumented (README, 选照片); read changes to it off the NAS's own web
  bundle (`/photo/assets/`) or its traffic, and test deletes only when the owner asks.
- Beyond the services named here, the app's one request of its own is for updates: the feed of this
  repository's GitHub releases (not GitHub's API, whose limit without a token a shared proxy uses up) and the APK it installs (`update/`), with nothing of the phone's.
- No keys in the build: OpenFreeMap tiles drawn by MapLibre, Photon for place names, Valhalla
  and OpenRailRouting for the routes of trips the API hasn't routed, the trip's stops alone.
  A service that needs a key takes the owner's own, typed into 同步与来源 and kept beside the
  API token, asked from the phone: in China the place picker's names and search come from
  AMap or Tencent with the owner's key; with both, the one the owner chose first and the
  other when it can't answer, Photon still answering without either, outside China, or once the keys'
  calls for the day are used up.
  APK size matters, so the release is arm64 only; weigh what a new dependency costs.
- Pin a newer library version only for a reason, and say why in the comment in
  `gradle/libs.versions.toml`.

