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

- Material 3, with Expressive where it fits, as an Android app, not a copy of the
  website's look. Before reworking a page, read the matching pages of m3.material.io
  (layout, tokens, interaction, styles, content design, the components in use) and
  follow them: M3 components and tokens over hand-made ones, color roles in their
  pairs, 48dp touch targets, scrims over media.
- m3.material.io is a script-built site: read it in the built-in browser, where its pages
  load whole in a same-origin iframe, the Guidelines, Specs and Accessibility tabs of each
  component as well as its overview. Spec values there (card 12dp corners and 8dp gaps,
  chips 8dp, sheets in surface container low) win over what the app had before.
- M3 is the only reference now. FlClash shaped the early pages and is still thanked in
  the README and on the settings page, but where it and M3 differ, M3 wins.
- Keep the smooth (continuous) corners; that has been decided twice.
- Settings, on a page or in a sheet, are M3 segmented lists (`SegmentedGroup` with
  `SwitchRow` and `ActionRow`), switches trailing, supporting text at most three lines;
  a longer explanation goes behind an info row's dialog, not on the page.
- Words are Chinese first, with English beside them in `values-en`, both following M3's
  content design, with TalkBack descriptions for what is shown. The Moments' own words
  are never changed.
- Pieces of a line (counts, a date and a place) are set apart by an em space, ` `,
  never by " · ": the site does the same.
- Share targets are named by what happens at the other end: 发布到 for one that ends in
  a public post (X, B 站, Threads, 小红书, 抖音), 分享到 for one sent to a chat
  (Telegram), and a destination that names itself (微信朋友圈, 飞书) alone.
- Follow what the app should be rather than the web version, except where it is asked
  to match: the map's features and the trip playback follow the site's player in
  `packages/trip-map/src/player`, with the route always moving while it plays.
- An exception to M3, asked for: the map's year colours are the site's palette as it is
  (`ui/map/Years.kt`, from `packages/trip-map/src/theme.ts`), in both modes and with white
  numbers on the dots, not harmonized with the primary or set at custom-colour tones.
  Change them on both sides together.

## Motion and navigation

- A page goes out the way it came in. A card grows into its page as an App Store card
  does, and the page shrinks back into it.
- Shared elements must not fight predictive back, drag-to-dismiss or paging in the
  viewer; check entering and leaving, and the in-between frames, on the phone.
- System back works everywhere a close button does, and back from any of the four
  tabs leaves the app.
- Pages lead to each other wherever they can, and a link to a page already under the
  current one goes back to it rather than opening it again.
- Show a refresh indicator only for a refresh the user pulled for.
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
  reviewed: one change set, like a commit. Don't add writes that bypass it.
- The ledger (记账 tab) is the phone's alone, like the footprints: nothing of it goes to the
  API, the site or Android's backup, and the only request it makes is for public exchange
  rates, when asked. It replaces iCost, so what iCost's export holds must keep importing:
  check a change to the importer against a real export with
  `ICOST_EXPORT=<xlsx> ./gradlew :app:testDebugUnitTest --tests '*LedgerTest*'`, and never
  commit an export.
- Sharing hands off to the other app and leaves posting to the user. Never post, pick a
  recipient or drive another app automatically. The one exception is the opt-in share
  assistant (Telegram, Threads): an accessibility service that only fills the composer
  and stops before sending. While debugging it, read its state with
  `adb shell dumpsys activity service cc.hyoban.moments/.share.TelegramShareService`,
  not `uiautomator dump`, which interrupts the service. Threads shows a single photo
  outside any `IgLazyRow`, and repeated shares stack photos into an open composer.
- No keys in the app: OpenFreeMap tiles drawn by MapLibre, Photon for place names.
  APK size matters, so the release is arm64 only; weigh what a new dependency costs.
- Prefer mature, maintained libraries over hand-rolled code. Pin a newer version only
  for a reason, and say why in the comment in `gradle/libs.versions.toml`.

