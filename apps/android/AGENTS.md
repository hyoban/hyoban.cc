# Working on the Android app

These instructions extend the repository's [shared guide](../../AGENTS.md) for
`apps/android`. They apply equally to Codex and Claude. Maintain durable Android
rules here and product behavior in [README.md](README.md).

Run the commands below from `apps/android`, with JDK 17 or newer and the Android
SDK configured in `local.properties` (`sdk.dir=…`).

## Check every change on the phone

- After each runtime or UI change, build the release and install it over what is there:
  `./gradlew :app:assembleRelease`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.
  It is signed with the debug key unless `local.properties` has a release key, so the
  app's data stays. Launch it and look at logcat's crash buffer.
- The phone is usually on wireless adb. Take its serial from `adb devices`, leaving out
  `emulator-*`, and never start the emulator while the phone is there. If the phone is
  locked or adb has dropped, say so instead of working around it.
- When `adb devices` and `adb mdns services` are both empty, restart the adb server
  (`adb kill-server`, `adb start-server`) first: the connect service often shows up then,
  and `adb connect` to it works.
- When `adb devices` is empty, `adb mdns services` may still show the phone's address
  (its pairing service, say) without the connect service: scan that address's ports
  30000–50000 for the open one besides the pairing port and `adb connect` to it; the
  phone is already paired.
- When the scan finds the port open and `adb connect` still says "No route to host",
  restart the adb server (`adb kill-server`) and connect again.
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
- A problem met along the way, a link that doesn't open or a row out of line, is fixed
  then and there rather than worked around or left for the user: find why, fix it, and
  check the fix on the phone.
- When a spec change drops or renames a field the app requires (as `published.id` went
  when Moments came to be named by uid alone), install the new app on the phone before
  pushing: a push deploys the API, and the app on the phone would stop syncing.
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

## Design

- The app follows its own design system, the 「这里 App 设计系统」 artifact, not Material 3:
  the site's restraint on a phone. The design is platform-neutral, one design for the iOS,
  Android and web versions to come: keep Android-only conventions out of it (name things by
  what they are, not by Compose or Material), and record only where Android implements it. Its tokens live in `ui/theme` (`Colors.kt`'s `Tokens`, the
  type scale and corners in `Theme.kt`); change the artifact and the code together. Text takes
  the design's 13 styles by their names (`titlePage` … `numCaption`), figures in the `num`
  ones, which are the system monospace; a day on a timeline, a ledger or a trip opens with its header row (`DayHeader`). The app
  doesn't depend on Material 3 (`androidx.compose.material3`, `glance-material3`): its
  components are its own, in `ui/design`, on Compose foundation, and it has no Material
  dependency at all. Don't add Material components or icon packs back.
- Icons are the design system's own set (`AppIcons`, 24 grid, 2 stroke, round ends and softened corners),
  generated into `ui/design/AppIcons.kt` by `python3 scripts/icons/kotlin.py` from
  `scripts/icons/icons.py`. A new icon is drawn there first, in the same hand, then
  generated; never edit `AppIcons.kt` by hand or reach for another icon library.
- `ui/design` holds the theme (`AppTheme`: colour roles, type, shapes, springs, the press
  wash in place of a ripple) and every component: text and icons, surfaces, buttons, chips, the
  segmented control, fields, list rows, menus, dialogs, sheets, the top bar, messages,
  pull-to-refresh and swipe-to-revert. Some keep Material's names so call sites read as before.
  A list laid out row by row (a lazy list) draws its group with `groupedRow`.
- The design rules themselves (layers and hairlines, the accent and the ink, few cards, the
  tabs, the timeline, top bars, settings groups, touch sizes, words, share targets, the two
  palette exceptions, motion and back) are the artifact's README and component cards; read
  them there rather than here. What follows is only where Android carries them out.
- Colours are `tokens.*` by the design's names alone; there is no layer of roles over them.
  Layers: the hairline is `cardBorder` and `dividerColor`, shadows `Elevation` drawn by
  `softShadow`, a surface's level `SurfaceLevel`.
- Keep only what is used: three buttons (`Button`, `FilledTonalButton`, `TextButton`), two chips
  (`FilterChip` to choose, `AssistChip` to act), and no icon that nothing draws.
- Top bars switch between what the page is one of with `TitleMenu` and put a control over the
  subtitle's figures in `subtitleAction`. Settings are `SegmentedGroup` with `SwitchRow` and
  `ActionRow`, switches `AppSwitch`. What floats over a photo or the map is `FloatingButton`.
- A 40dp button keeps its 48dp target in the layout (`minimumInteractiveComponentSize`); a 36dp
  chip is laid out at its own size, so rows of chips stand 8dp apart, Compose widening its target.
- The design's gaps are `Space.item`, `Space.section` and `Space.day`; take them rather than a size
  where a gap means one. Large system fonts are `isLargeFont()` (130% and up), so read it in composition.
- Words are in `values` (Chinese) and `values-en`, with TalkBack descriptions for what is shown.
- The map's year colours are `ui/map/Years.kt`, from `packages/trip-map/src/theme.ts`: change both
  sides together. The ledger's charts are drawn on a Compose `Canvas` (`ui/ledger/Charts.kt`), not
  with a chart library.
- Follow what the app should be rather than the web version, except where it is asked
  to match: the map's features and the trip playback follow the site's player in
  `packages/trip-map/src/player`, with the route always moving while it plays.

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
- The site's fog (`packages/trip-map/src/map/fog-layer.ts`) and the app's sweep the same
  way: change both together.
- MapLibre drops a filter or expression it can't take without throwing, leaving the layer
  unfiltered; after setting one, look for `Error setting filter` from `Mbgl` in the app's
  logcat. The regions of the rest of the world in the fog's asset all have the id
  `outside`, so tell them apart by `cx` and `cy`, never by id.

## What the app is

- A viewer of the site's Moments, places and trips, kept on the phone so it opens at
  once and works offline. Moments can be written on the phone, local first.
- Nothing reaches the site until 全部发布到网站, after the changes waiting have been
  reviewed: one change set, like a commit, sent as entries to `POST /v2/changes` (the first
  shape to `/v1` on an API without it). Don't add writes that bypass it.
- The ledger (its bills on the timeline, its pages under 我) and the footprints are the
  phone's, out of Android's backup, and private by default: a bill goes out only when its book
  or itself says public, as its kind, amount, currency, category and note, never its account or
  balance; a span of the footprints only when made public, as its simplified line. Both go
  with 全部发布到网站 like everything else (`data/store/Outside.kt`); the ledger's only other
  request is for public exchange rates, when asked, besides naming where the footprints put a
  bill with no place (`footprints/BillPlaces`), which sends Photon the point alone. Private and unpublished are two things,
  said 私有 and 未发布, never 仅本机. It replaces iCost, so what iCost's export holds must keep importing:
  check a change to the importer against a real export with
  `ICOST_EXPORT=<xlsx> ./gradlew :app:testDebugUnitTest --tests '*LedgerTest*'`, and never
  commit an export.
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
- Prefer mature, maintained libraries over hand-rolled code. Pin a newer version only
  for a reason, and say why in the comment in `gradle/libs.versions.toml`.

