# Working on the Android app

The README describes what the app does and how it is laid out; keep it current, in its
own prose, whenever a change alters what the app does. This file is how the work on it
goes, as it has gone so far.

Keep this file current as the work goes on. When a conversation settles something that
should hold beyond it (a correction, a preference, a decision made or taken back), add it
here in the same turn, or change or remove the line it replaces. Say so in the reply, and
commit it with the next commit. One-off requests and things the README or code already
say stay out.

## Check every change on the phone

- After each change, build the release and install it over what is there:
  `./gradlew :app:assembleRelease`, then `adb install -r app/build/outputs/apk/release/app-release.apk`.
  It is signed with the debug key unless `local.properties` has a release key, so the
  app's data stays. Launch it and look at logcat's crash buffer.
- The phone is usually on wireless adb. Take its serial from `adb devices`, leaving out
  `emulator-*`, and never start the emulator while the phone is there. If the phone is
  locked or adb has dropped, say so instead of working around it.
- Look at it, don't just build it: `scripts/ui.py open|dump|tap|back`, the `moments://`
  links and screenshots. Check dark mode and landscape when a change touches colour or
  layout, and watch for dropped frames when it touches motion.
- A problem met along the way, a link that doesn't open or a row out of line, is fixed
  then and there rather than worked around or left for the user: find why, fix it, and
  check the fix on the phone.
- Before committing run what CI runs (`.github/workflows/verify.yml`):
  `./gradlew :app:testDebugUnitTest :app:assembleDebug`.

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
- Words are Chinese first, with English beside them in `values-en`, both following M3's
  content design, with TalkBack descriptions for what is shown. The Moments' own words
  are never changed.
- Share targets are named by what happens at the other end: 发布到 for one that ends in
  a public post (X, B 站, Threads, 小红书, 抖音), 分享到 for one sent to a chat
  (Telegram), and a destination that names itself (微信朋友圈, 飞书) alone.
- Follow what the app should be rather than the web version, except where it is asked
  to match: the map's features and the trip playback follow the site's player in
  `packages/trip-map/src/player`, with the route always moving while it plays.

## Motion and navigation

- A page goes out the way it came in. A card grows into its page as an App Store card
  does, and the page shrinks back into it.
- Shared elements must not fight predictive back, drag-to-dismiss or paging in the
  viewer; check entering and leaving, and the in-between frames, on the phone.
- System back works everywhere a close button does, and back from any of the three
  tabs leaves the app.
- Pages lead to each other wherever they can, and a link to a page already under the
  current one goes back to it rather than opening it again.
- Show a refresh indicator only for a refresh the user pulled for.

## What the app is

- A viewer of the site's Moments, places and trips, kept on the phone so it opens at
  once and works offline. Moments can be written on the phone, local first.
- Nothing reaches the site until 全部发布到网站, after the changes waiting have been
  reviewed: one change set, like a commit. Don't add writes that bypass it.
- Sharing hands off to the other app and leaves posting to the user. Never post, pick a
  recipient or drive another app automatically, and never use an accessibility service.
- No keys in the app: OpenFreeMap tiles drawn by MapLibre, Photon for place names.
  APK size matters, so the release is arm64 only; weigh what a new dependency costs.
- Prefer mature, maintained libraries over hand-rolled code. Pin a newer version only
  for a reason, and say why in the comment in `gradle/libs.versions.toml`.
- A refactor asked for "without changing anything" changes no behavior.

## Commits

- Commit and push to `main` when asked ("提交并推送"). Add files by name: never
  `plans/` or `.claude/`.
- Keep the message short: one lowercase line saying what changed, around 50–70
  characters, with no body and no Co-Authored-By trailer. The long single-sentence
  messages in older history are no longer the style.
