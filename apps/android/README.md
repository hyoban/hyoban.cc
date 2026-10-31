# Here, Moments for Android

The app that keeps the site's Moments, places and trips on the phone, lets new ones be
written there, and adds what stays the phone's own: a ledger and GPS footprints. Its look
is the app's design system (the 「这里 App 设计系统」 artifact, mirrored in `ui/theme`).

## Reading

It reads the API's public endpoints (`apps/api`) and keeps a copy under the app's `api/`
directory in the layout `packages/data-spec` defines, so it opens at once and works
offline. Each start, each pull down on a list, and 刷新 in the settings ask only for what
changed since: the Moments, places and trips from `/v1/changes`, and what only the second
shape has (charges, public bills and marks, collections, public tracks) from `/v2/changes`.
The routes (`GET /v1/routes`) are asked for again only when the API's revision has moved
on. A new API, set in the settings, drops the copy and reads everything again.

## Pages

Three tabs float at the foot of the screen, with 记录 beside them; on a window 840dp wide
or more, or a phone on its side, they stand down a rail instead.

- **时间线** is everything newest first, as a conversation with oneself: each day opens
  with its date in a capsule, Moments are bubbles from the right with their photos across
  the top, and the day's bills, charges and footprints come from the left in one records
  bubble. A banner marks where each trip begins. Scrolling folds the tabs away.
- **地图** shows the places, the trips' routes (listed in the sheet at its foot) and the
  footprints. 迷雾地图 lays a fog over it, as the site's map does, thinner over a city the
  more Moments it has, from the site's `apps/web/src/data/boundaries/cities.json` shipped as an asset.
- **我** holds the changes waiting to be published, this month's spending, and rows for
  账单与资产, 足迹, 记录项与公开 and 设置.

The pages lead to each other wherever they can: a place or a trip to the map, a day's pill
to the day, a Moment's menu to its place, trip and day. A day's page opens on that day's
footprints on a map. A trip's page has 回放旅程, which plays the trip back as the site's
player does (`packages/trip-map/src/player`): the route never still, a vehicle at its tip,
a card per Moment stepped through from a toolbar, by a swipe or with a keyboard's arrows
and Space. 编辑旅程 changes its name, days, public switch, Moments and covers, published
as a collection.

No page has a bar: its way back and actions float as circles over it, its title large
under them. On a wide window the map's trips stand in a column and bottom panels open as
dialogs.

The search (`moments://search`) filters by kind (Moments, bills, charges) and by year and
month, and lists what it finds under its years.

## Writing a Moment

记录, or `moments://new`, opens a page of its own, or a dialog on a wide window. A Moment
has its words, up to nine photos and videos, a time and a place, and under 加一项 a meal
(美食: the dishes, 好吃, 一般 or 踩雷, the cost and how many it fed), a bill (账单) and a
kind (类型: 技术, 旅行, 美食, 用车, 摄影 or 生活; the site doesn't show 技术). It is 公开 or
私有; a private one is published for its owner but never shown on the site.

- **Media.** Picked with the system's documents picker, which keeps a photo's location,
  or shared in from the gallery (Here (写一个时刻)). They are copied in whole; a motion
  photo is split into a live photo's still and video. A video over 100 MB, or other than
  MP4, MOV or WebM, is refused, since the Worker and the site's poster can't take it. A
  photo marked 不用作封面 never becomes a cover, widget or share card.
- **Time.** The earliest photo's EXIF time and offset, else a video's creation time, else
  the files' dates, else now.
- **Place.** Where the first photo was taken, else where the phone is: the known place
  within 2 km, else a new place named through Photon (`BuildConfig.PLACE_SEARCH_URL`)
  after the nearest park, sight, shop or street, with the system's geocoder as fallback.
  It can be moved on a map with a pin. Its kind follows OpenStreetMap and can be changed,
  for a known place too. A place shared from Amap (Here (作为时刻的地点), or 保存为地点 to
  only keep it) is followed from its short link and shifted from GCJ-02 back to WGS 84.

New Moments and places live under the app's `local/` directory in the spec's layout, the
places with provisional ids (`p-<ulid>`), marked 未发布 until published.

## Publishing

With the API's token, set under 我 → 时刻与数据 (in a file backups leave out), the site's
Moments can be changed and deleted too. Nothing reaches the site as it is saved: new,
changed and deleted Moments, changed places, public bills, trips and footprint spans wait
on the phone, past a restart. 发布更改, from the card at the top of 我, lists them, shows
what changed and takes a change back. 全部发布到网站 runs as WorkManager work in a
foreground service with a progress notification, waiting out a dropped link: it uploads
the new originals a few at a time, then sends everything else as one `POST /v2/changes`
(`/v1` on an API without it), which the API takes whole or not at all. What is published
leaves the phone's store for the site's.

## Sharing

A Moment's share sheet hands a copy of its words and a choice of photos to another app,
which leaves the recipient and the final send to the user. It never posts. A meal's copy
ends with a line of its place, each one's share of the bill and its verdict (好吃, 一般 or
踩雷), each behind an emoji, which can be edited away like the rest.

| Destination    | How                                                                                     |
| -------------- | --------------------------------------------------------------------------------------- |
| 发布到 X       | the X app's composer, up to four photos (stills of live photos, no video)              |
| 发布到 B 站    | Bilibili's dynamic composer (`FollowingPublishActivity`) with text and photos          |
| 分享到 Telegram | Telegram's share receiver; with several photos the text is copied to paste as caption |
| 发布到 Threads | `barcelona://create?text=…` for text, else its share receiver with the text copied     |
| 发布到小红书   | its share receiver, the text copied; text alone opens 写想法                           |
| 发布到抖音     | `SystemShareActivity` into the photo editor, the text copied; needs a photo            |
| 微信朋友圈     | `ShareToTimeLineUI` for text or one photo; more go through 准备图文                    |
| 飞书, 准备图文 | the photos saved to a `Pictures/Here Share-…` album and the text copied                |

These entries depend on each app's version; when one fails, 准备图文 is offered with the
edited text and selection kept. Each share has its own cache directory, cleaned up after
a week, and the read grants given to the receiving apps are revoked with it.

The opt-in 分享与记账助手（试用） is an accessibility service
(`accessibility/ShareAccessibilityService`) that, for Telegram and Threads only, fills
the composer (Telegram: finds the `@username` given and the caption) and stops before
sending. It reads only the app of the share it was asked for, gives up after 40 seconds
and keeps nothing. Each destination is an `AssistAdapter` with an `AssistFlow`
(`share/TelegramShareAdapter`, `share/ThreadsShareAdapter`); add an app the same way,
with fixtures for its pages. The manifest keeps the `share/TelegramShareService` name
only so the permission already granted to it stays.

## Ledger

记账 is a ledger kept on the phone alone, in place of iCost: books of bills over accounts
in their own currencies. Its page (`moments://ledger`, from 我) has three tabs:

- **账单**, the book's bills under their months and days;
- **统计**, a month, a year or all of it: spending, earning and what is left, and charts
  drawn on a canvas (`ui/ledger/Charts.kt`) by day, by category and by weekday;
- **资产**, net assets, a line of the last twelve months, and the accounts by kind.

The book's name switches books or shows 全部账本. The eye beside the sums hides every
amount (隐藏金额), here and on the other pages.

A bill is an expense, an income or a transfer (also a repayment or a loan), or a refund or
reimbursement written from its expense. `moments://new-bill`, or the ledger's +, opens one
on a keypad (sums like `12+3.4` add up). It has an amount in any currency, a category,
one account or several with their shares, its time and book, discounts and fees, whether
it is to be reimbursed or left out of the sums, and the Moment, place and trip it belongs
to. A bill with no place is placed by the footprints nearest its time (within a quarter of
an hour, good to 100 m), named through Photon from the point alone and kept in
`footprints.sqlite`. An account's 校准余额 sets its balance by moving its opening balance.

The ledger meets the Moments: a Moment's page lists its bills, a meal with no bill offers
记一笔, a trip's and a day's page show what was spent, and 关联时刻和旅程 links meals and a
travel book's bills to their Moments and trips.

记账设置 manages the books and their categories, the exchange rates and the data:

- **从 iCost 导入** reads the .xlsx of iCost's 账单导出 (picked, shared in or opened with the
  app), shows what it found before writing, and skips what is already there, so each newer
  export brings the ledger up to date. Tags become `#tag` in the note. The export has no
  balances, so accounts are calibrated after. Recurring bills, installments, savings plans,
  templates and photos aren't carried over.
- **Rates** are the European Central Bank's, bundled in `assets/ledger-rates.json` and
  refreshed from Frankfurter only when asked.
- **备份到文件** and **从备份恢复** write and read the whole ledger as JSON; **导出 CSV**
  writes the bills in iCost's columns.

`ledger/LedgerStore` keeps it in `noBackupFilesDir/ledger.sqlite`, outside sync and
Android's backup. Bills are private: 记录项与公开 (`moments://items`) makes a book's new bills
public, and a bill's 公开 chip says otherwise for one. A public bill publishes only its
kind, amount, currency, category and note (`data/store/Outside.kt`). `ledger/ICostImport`,
`ledger/Accounting` and `ledger/Links` are plain Kotlin tested in `LedgerTest`, which also
reads a real export when `ICOST_EXPORT` names one.

Opening a bill form waits for the local ledger to load before checking for a book. It
creates a default book only when none exists, including when several forms initialize
at once.

**自动记账（试用）**, switched on in 记账设置, has the same accessibility service read WeChat
Pay's and Alipay's pages. On one that says a payment went through, it offers a 记一笔
notification with the amount, time, note and account it read; tapping it opens
`moments://payment?amount=…&time=…&note=…` with a guessed category. Categorization matches
expense category names across all books contained in the note before falling back to fixed
keywords; past bills do not affect it. Longer names win; equal-length names prefer a
subcategory. A matched category also selects its book in the form.
WeChat notes combine the payee above the amount and the 商品 row, separated by a space;
identical values appear only once. Without 商品, the payee alone is kept.
Nothing is written until saved, and a payment matching a kept bill within ten minutes
isn't offered. It never taps, types or keeps the page. `ledger/Payments` reads the page, with fixtures in
`PaymentsTest`; `ledger/AutoBill` keeps the switch and posts the notification.

**侧键自定义**, in 我 → 设置 (`moments://side-key`), assigns an action separately to a
single press, double press and long press of the physical OPPO key. It includes payment
capture, toggling the flashlight, opening an installed app or a link/app URI, play/pause,
next/previous track, volume, back, home, recent apps, notifications, quick settings and lock screen. No gesture
is assigned on the user's behalf. Double press defaults to a 300 ms window, adjustable
from 150 to 600; long press to 600 ms, adjustable from 400 to 2000. Single press waits for
the double-press window, and long press runs once. A tap followed by a hold runs long
press alone. The flashlight action toggles the rear flash without opening the camera or
requesting camera permission, reads its current system state each time (including changes
from Quick Settings), and reports when the flash is unavailable, such as while a camera
is using it. It does not keep a camera-state listener running between presses.

Start Shizuku and enable the app's existing share-and-ledger accessibility service,
then turn on the side-key switch. The listener uses a small Shizuku UserService with
shell privileges to find the input device advertising `BTN_TRIGGER_HAPPY32` and read its
press/release events, without root or a fixed device number. It is hosted by the existing
accessibility service, with no separate foreground service or notification. Switching it
off or disconnecting accessibility stops the reader and restores the saved system action.
Shizuku loss leaves restoration pending until it is available and authorized again. A
phone restart needs Shizuku started again; while the switch stays on, the listener resumes
when both services are ready. Unsupported devices report a connection failure. Stop any
standalone side-key remapper before enabling this one.

The payment action directly calls the same accessibility reader while WeChat or Alipay
is still in front, then opens the prefilled confirmation; it does not require the auto-bills
switch. The phone must be unlocked. An already saved payment is reported instead of opened
again. Nothing is saved until the user confirms. There is no cross-app capture broadcast
or signature permission anymore. The reader never keeps a payment page, and the side-key
settings and original system action are excluded from Android backup and device transfer.
`sidekey/GestureDetector` and `SideKeyActions` are covered by timing and validation tests.

## Footprints

The footprints are GPS tracks kept on the phone alone, in
`noBackupFilesDir/footprints.sqlite` (`footprints/FootprintStore`), outside sync and
backup. 地图 → 足迹 opens a panel to record, pick a day or trip, export and import GPX and delete;
足迹设置 (`moments://footprint-settings`) sets how recording behaves.

- **Recording.** 日常自动记录 runs until turned off; 单次行程 marks a trip over the same
  samples; 暂停所有记录 pauses both. A foreground service with precise location asks for a
  fix every 5 seconds (a trip: 5 s / 5 m); after a good fix daily recording allows up to 60
  seconds of batching, falling back when batches stop arriving and retrying with backoff;
  when they stop because the receiver sees too few satellites (a garage, a tunnel), it
  searches again unbatched and batches again after the next fix, without the backoff.
  It resumes when the app opens, and after a reboot when background location is allowed.
- **Resting.** Daily recording rests GPS once several fixes over three minutes show no
  movement, and wakes on the significant-motion sensor, on passive or low-power network
  positions showing it moved, or on a backed-off probe (two to ten minutes). While it
  rests the notification moves to a silent channel and says 省电等待. The optional
  活动识别增强 uses Play services' activity transitions as an extra wake-up, never as a
  reason to rest; while they say moving, a search that finds no fix (a garage, a tunnel)
  keeps going for up to ten minutes since the last movement instead of resting after two.
- **Storage and refinement.** Every fresh, valid fix is stored raw. `TrackRefiner` derives
  the map's line and ordinary GPX from them: it drops spikes and stationary clouds, and
  breaks the line at fixes over 50 m uncertainty, gaps over 45 seconds, pauses and
  restarts. 导出当前原始采样 GPX exports every stored sample of the chosen day or trip, and
  导出全部原始采样 GPX those of every day, with accuracy and speed in
  `https://hyoban.cc/ns/footprints/1` extensions: the full backup to import again.
- **Import.** 导入 GPX 轨迹 reads a GPX the app exported (`footprints/TrackImport`), a line
  or raw samples, back into the store as samples of no trip: raw samples keep their
  segments and extras, each `trkseg` of a line becomes a segment. Where the phone already
  has samples within 45 seconds, its own are kept and the file's skipped, so importing a
  file twice adds nothing; a raw export before the line of the same day keeps the most.
- **What else is kept.** Beside each sample the store keeps what the fix and the receiver
  said: heading and its accuracy, vertical accuracy, altitude above sea level, the provider,
  and the satellites used and seen with their signal (matched by time, so batched fixes
  get their own); the raw GPX carries them too. With 活动识别增强 on it keeps every activity
  transition, and it keeps recording events: each command with who gave it (`manual`,
  `notification`, `wifi`, `bluetooth`), each rest with its reason, each wake with what woke
  it, and each lost sky. Deleting a day or trip deletes these over its span too.
- **Moves and stays.** `TrackParts` reads the refined points of the chosen day or trip as
  stays (five minutes or more within 120 m, a pause or a rest inside one included) and moves
  between them, each told walking, cycling or driving by the kept activities where there are
  any, else by its speeds, and never against them (a walk can't go at a drive's pace); slow traffic and
  a car park's crawl stay part of the drive. The map draws a drive solid, a ride thinner, a
  walk dotted and a stay as a ring; 足迹 → 行程分段 lists them with distance, average and top
  speed, names a stay after one of the phone's own places within 300 m (no request leaves
  for it), and frames one on the map when tapped.
- **Notification.** Its icon shows the state (recording, waiting for a fix, location off).
  On Android 16 and later it asks to be a Live Update; on ColorOS that also needs
  流体云显示实时活动, linked from 足迹设置 → 状态栏显示.
- **Diagnostics.** `noBackupFilesDir/footprint-diagnostics/` keeps two rotating JSONL files
  of 8 MiB, about two days, with each request, fix, filter decision and state change, never a coordinate,
  network name or token. 足迹设置 → 诊断 → 导出诊断日志 exports them with a replay of the
  latest day through the current filter.
- **Publishing.** A trip's page has 公开这段旅程的轨迹: at the next 全部发布到网站 its line goes
  out simplified to 30 m (`track_shares`), and the site draws the trip along it. The raw
  samples never leave the phone.

### Connection automation

足迹设置 → 连接自动控制 starts and pauses recording by connections the user picks, none by
default: joining a chosen Wi-Fi pauses and leaving all of them starts; connecting a chosen
Bluetooth device starts and disconnecting the last pauses, and Bluetooth wins over Wi-Fi.
Changes settle for five seconds; a manual choice made since always wins. A `specialUse`
foreground service watches the connections without scanning or asking for fixes, and on
Android 16 a chosen device can be associated through `CompanionDeviceService` to wake it.
It needs precise and background location, and Nearby devices for Bluetooth. Its settings
are `noBackupFilesDir/connection-reminders.json` and `companion-connections.json`.

## Elsewhere

- **Widgets.** 那年今天 (a Moment from this day in an earlier year) and 旅程 (the trip under
  way, else the last or next), in ColorOS's card sizes, drawn from the copy after each sync
  and every three hours.
- **Video.** The viewer goes picture-in-picture when the app is left while a video plays.
- **Language and appearance.** Always the phone's: Chinese, or English when the phone speaks
  it, and light or dark as the system is. The API, the media origin and the map's styles
  default to `BuildConfig` and can be changed in the settings.
- **Maps.** OpenFreeMap's vector tiles drawn by MapLibre, needing no key.
- **Name and icon.** Here, in English whatever the phone speaks: a white cat with a grey cap
  peeking in from the lower left on blue, an adaptive icon with a monochrome layer.

## Building

Android Studio opens `apps/android` as a project. From a shell, with the Android SDK in
`local.properties` (`sdk.dir=…`) and JDK 17 or newer:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The unit tests round-trip every record under the repository's `data/` through the app's
models, so the app and the site can't drift apart unnoticed, and check the media URLs,
trip membership and the sync against a stand-in API.

`RecordingRecoveryTest` under `src/androidTest` checks the recorder's recovery and pause
races with a mock GPS on an emulator (it skips physical devices):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class cc.hyoban.moments.footprints.RecordingRecoveryTest cc.hyoban.moments.test/androidx.test.runner.AndroidJUnitRunner
```

## On the phone, from adb

Every page has a link, `moments://moments`, `map`, `trips`, `settings`, `new`,
`moment/{uid}`, `trip/{id}`, `play/{id}`, `day/{YYYY-MM-DD}` and `place/{id}`, which
`adb shell am start -d` opens. Buttons, tabs and cards carry test tags that
`uiautomator dump` reports as resource ids (`tab_map`, `settings`, `back`,
`moment_{uid}`, `media_{uid}_{index}`, `trip_{id}`, `day_{date}`, …), and
`scripts/ui.py` uses both:

```sh
scripts/ui.py open moment/01M466J3KG95G3V62CZ1C7ZZBH
scripts/ui.py dump
scripts/ui.py tap tab_map
```

## Layout

- `data/spec`: the records as Kotlin, each keeping the fields it doesn't know.
- `data/api`: the API's change feeds and writes.
- `data/store`: the copy on the phone and what the screens are given from it.
- `data/local`: the Moments written on the phone, their time and place.
- `data/media`: photo, poster and video URLs, picked as the site picks them.
- `data/trips`: which trip a Moment is on, its days and stops, and its playback.
- `publish`: the publishing work.
- `ledger`, `footprints`, `share`, `accessibility`: the ledger, the footprints, sharing
  and the assistant.
- `widget`: the home screen widgets (Glance).
- `ui`: the Compose screens; `ui/design` and `ui/theme` the design system.

## Thanks

The cat on the icon is 馒头 (Mantou), drawn from a photo of the real one. The settings
page says so too, with a photo of its face.
