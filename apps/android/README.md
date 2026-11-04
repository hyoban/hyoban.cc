# Here, Moments for Android

The app that keeps the site's Moments, places and trips on the phone, lets new ones be
written there, and adds what stays the phone's own: a ledger and GPS footprints. Its look
is the app's design system (the 「这里 App 设计系统」 artifact, mirrored in `ui/theme`).

## Reading

It works on the phone alone: the build names no API, so until an API's address and its
token, which every request needs, are set under 我 → 同步与来源, it asks nothing of one and
everything written stays on the phone. With them it reads the API (`apps/api`) and keeps a
copy of every row D1 has but the trips' routes, in the same tables (`copy.db`, out of the
backup, its tables generated from `@hyoban/data-spec/tables` into
`data/copy/CopyTables.kt`), so it opens at once and works offline. A sync rebuilds what the
screens show only from the rows it brought, and skips a table the copy doesn't keep. The
copy only follows the API: each start, each pull down on a list, and 刷新 in 我 → 同步与来源, and
each publish ask only for the rows changed since, from `/v2/rows`. The screens take the
entries as Moments when they have words and as they are otherwise (charges, public bills,
marks), with the places, collections and public tracks; the trips are worked out from the
collections and their marks, as the spec's `collectionToTrip` does. It is the platform's
SQLite, through `androidx.sqlite`'s driver: from Android 12, the oldest the app runs on, it
has the tables' generated columns. The routes (`GET /v2/routes`) are asked for again only
when the API's revision has moved on. A new API, set in 同步与来源, drops the copy and reads
everything again.

A trip the API hasn't routed, every trip without an API and one made on the phone with one,
is routed on the phone the way the API routes the rest (`data/trips/PhoneRoutes.kt`): its
drives along the road FOSSGIS's public Valhalla finds, its trains along the rail line
OpenRailRouting finds between the places' stations, its ferries and flights straight. Neither
asks for a key. The legs are asked for one at a time, a second apart, once a trip's stops have
stayed put for two seconds, and kept in `phone-routes.json` by the API's keys; a leg with no way
along it is drawn straight and not asked for again, and one that failed is asked for the next
time the trips change.

## First run and permissions

The first time the app opens with no API set, 初次打开 (`ui/onboarding`) goes over it once:
where to start (连接我的网站 with the API's address and token, which reads everything in the
background and is forgotten again if it can't be read; 从别的 App 导入, the 导入 page with 继续 at
its foot; or 从空白开始), then 记录足迹, the only place it asks for location, and only when
footprints are turned on. Given location only while in use, it says recording stops in the
background and links to the system's settings. `moments://onboarding` shows it again.

Every other permission is asked where it is used, with a `PermissionSheet` first saying what it
is for and what stays on the phone (`rememberPermissionAsk` in `ui/components/Permissions.kt`):
location from the map's locate button and the place picker (which asks on opening only the first
time, and otherwise lists 定位没有打开 at the top), where photos were taken before the first pick,
location and notifications for footprints, physical activity for 活动识别增强, Nearby devices for a
Bluetooth device in 连接自动控制. Refused for good, the sheet's button opens the app's settings
instead, and coming back carries on. Location 始终允许, the accessibility service for 自动记账 and
Shizuku for 侧键 are the system's settings to give: their sheets name the way there. 足迹记录 lists
what recording is missing in 需要 at its top. Notifications for publishing are asked directly.

## Pages

Three tabs float at the foot of the screen, with 记录 beside them; on a window 840dp wide or
more, or a phone on its side, the same capsule stands on its end down the side, 记录 under it:
on a phone on its side, down the side away from the camera, so it reads the same turned
either way. On a phone on its side the status and navigation bars are put away (a swipe from
the edge shows them), the timeline is a conversation beside the tabs with the day it has
scrolled to on a map filling the rest, the map's trips float in a card beside the tabs, and
sheets slide in from the side. A Moment's photo, a day's footprints, a trip's route and a
place's map take the half on the camera's side, running under its cutout, as does the
timeline's map of the day, and the playback's card stands across from the camera, the whole
height, always open; 记录, 编辑旅程 and 搜索 (its filters) stand in two columns; the ledger, 发布更改, a
bill, 设备, 订阅 and 车 keep their figures in a column at the left beside their list, the
ledger's segments in the bar; 我 is two columns; 记一笔's keys take the right half; 记录 is a page
rather than a dialog. Controls keep clear of the camera's cutout on whichever side it is.

- **时间线** is everything newest first, as a conversation with oneself: each day opens
  with its date in a capsule, Moments are bubbles from the right with their photos across
  the top, and the day's bills, charges and footprints come from the left in one records
  bubble. A banner marks where each trip begins, or, for the trip under way, says
  进行中 and which day it is. Scrolling folds the tabs away.
- **地图** shows the places, the trips' routes (listed in the sheet at its foot) and the
  footprints. Search sits at the top beside the fog's switch, over a legend of the years;
  the footprints and the phone's place are buttons down the side. A place tapped fills the
  sheet with its Moments and the way there; a route tapped floats its trip's card, which
  opens the trip or plays it back. 迷雾地图 lays a fog over it, as the site's map does, thinner
  over a city the more Moments it has, from the site's
  `apps/web/src/data/boundaries/cities.json` shipped as an asset.
- **我** is one screen: the changes waiting to be published, then three rows, 账本 (with this
  month's spending under its name), 足迹 and 东西, and one group of 设置: 记录项与公开, 家,
  同步与来源, 导入 and 侧键, each a page with what it is set to under its name. There is no
  settings page over them, and under 我 there is one level of pages: what goes deeper, such as
  a book, the exchange rates or a side key gesture's action, opens over its page as a sheet or
  a dialog. The ledger's own settings (记账) open from the ledger's top bar and the footprints'
  (足迹记录) from the footprints panel, beside what they are about. Theme and language have no
  setting; they follow the phone.
- **记录项与公开** (`moments://items`) opens on the 记录项, the modules the spec defines
  (`data/spec/Modules.kt`, mirroring `modules` in `@hyoban/data-spec/entry`): 足迹, 账本, 东西
  (设备 and 订阅), 车, 美食 and 签到, each a switch, all on until turned off. One turned off is
  put away everywhere: its rows on 我, its pages, its records on the timeline, a day, a trip,
  a Moment, the search's kinds and the map, its chip under 加一项, its sources under 导入, the
  side key's payment action and the widgets' counts (导入 lists the sources left, and goes
  from 我 with none); its background work stops (the
  footprints' recording and connection reminders, 自动记账), and its data stays on the phone,
  so turning it on again brings everything back as it was, the recording included. Under the
  modules, each book's and the footprints' publishing defaults (below, Ledger and Footprints).
- **东西** in 我 is one page that switches between 设备, 订阅 and 车 from its title, 车 only with
  an API; it opens on the one looked at last. 设备 (`moments://devices`) lists what is
  owned and what was sold, each with what it costs a day; 订阅 (`moments://subscriptions`)
  what is paid by the month, by the year and once. Each device and subscription is an entry
  with its plugin's part, edited in a sheet and published with the rest from 发布更改. 车
  (`moments://car`) shows a month of the Zeekr import at a time, its days, charges and
  assisted driving, and is read-only: only an import changes the car's data. On the timeline
  a device comes as a record on the day it was bought, a subscription on the day it was last
  paid, first in its day's records bubble; tapped, it opens its sheet.

The pages lead to each other wherever they can: a place or a trip to the map, a day's pill
to the day, a Moment's menu to its place, trip and day. A day's page opens on that day's
footprints on a map, its bills and charges read down by their times, and today's says 还在记录
while the footprints are recording. A trip's days and each day's Moments read from earliest
to latest. A trip's page has 回放旅程, which plays the trip back as the site's player does
(`packages/trip-map/src/player`): the route never still, a vehicle at its tip, a card per
Moment stepped through from a toolbar, by a swipe or with a keyboard's arrows and Space.
编辑旅程 changes its name, days, public switch, Moments and covers, published as a collection;
its days' Moments are counted in one row that opens them, each with a switch to leave it
out, and those of the two days either side are listed under it, out until added. The + on
the map's trips makes one (新建旅程, `moments://trip-edit`) on the phone alone: a collection of
its own, its id its uid in lowercase as the API will know it, shown and played back as the
site's are. Until then it can be deleted from its 编辑旅程; once published it is the API's like
the rest.

Below the trips, after a hairline, 可能的旅程 are the trips the records suggest
(`data/trips/TripDiscovery.kt`, with `TripEvidence.kt` and `TripNames.kt`): runs of days
away from home, with evidence of a visit beyond passing through, from the check-ins and the
Moments with places, not the footprints; nothing leaves the phone. Home is where the records
fall on the most days around each one, so it follows a move; 我 → 家 (`moments://homes`)
shows it and takes more by hand, a city or a whole province each. The best come first, by
photos and days with records, each named after the cities visited, with the holiday it spans
(国庆香港) or 一日 or 周末 (南京一日). Its + adds it as it is, private as the check-ins it came
from; opened, it is 新建旅程 with its days and name filled in; one dismissed doesn't come back.
The thresholds live in the code and its tests.

No page has a bar: its way back and actions float as circles over it, its title large
under them. On a wide window the map's trips stand in a column and bottom panels open as
dialogs.

The search (`moments://search`) filters by kind (Moments, bills, charges) and by year and
month, and lists what it finds under its years; with nothing to list it says why and
what to try next.

## Writing a Moment

记录, or `moments://new`, opens a page of its own, or a dialog on a wide window. A Moment
has its words, up to nine photos and videos, a time and a place, and under 加一项 a meal
(美食: the dishes, 好吃, 一般 or 踩雷, the cost and how many it fed) and a bill (账单). It
is 公开 or 私有; a private one is published for its owner but never shown on the site.
The home screen widgets are the owner's own and show private Moments and trips too,
except in 演示模式.

- **Media.** Picked with the system's documents picker, which keeps a photo's location,
  or shared in from the gallery (Here (写一个时刻)). They are copied in whole; a motion
  photo is split into a live photo's still and video. A video over 100 MB, or other than
  MP4, MOV or WebM, is refused, since the Worker and the site's poster can't take it. A
  photo marked 不用作封面 never becomes a cover, widget or share card.
- **Time.** The earliest photo's EXIF time and offset, else a video's creation time, else
  the files' dates, else now.
- **Place.** Where the first photo was taken, else none until one is chosen: the page itself
  never locates the phone. A photo's place is the known place within 2 km, else a new place
  named after what is nearest: in China, with the owner's AMap or Tencent key (高德地图 Key,
  腾讯地图 Key, under 同步与来源; with both, the one chosen under 国内地点来源 first and the other when it
  can't answer), the shops, sights and buildings that service knows, asked from the phone
  with the pin's point: their place searches first, which know the shops nearest, then, once
  those calls are used up for the day, their reverse geocoding, whose calls are thirty times
  as many but which knows mostly sights and buildings (a search by name asks their input
  tips), else the nearest park, sight, shop or street Photon knows
  (`BuildConfig.PLACE_SEARCH_URL`), with the system's geocoder as fallback. Tapped, the
  place opens on a map with a pin at its middle, which finds where the phone is when there
  is no place yet to open on. Under the map are a search and one list: the choice first and
  ticked, then the known places close by and what is around the pin, nearest first, or while
  something is typed what is called that from the same sources. A row tapped is chosen and
  the pin moves there; a result chosen leaves the search as it was, the choice above the
  rest, so another is a tap away. The choice tapped again is named by hand, and its kind,
  which follows where it came from, opens a sheet of every kind by group (餐饮, 购物, 住宿, 景点,
  娱乐, 交通, 生活, 地区), for a known place too: a café, a metro station or a park rather than only
  food, transport or a sight, each with its icon in the list. A place picked from AMap or
  Tencent keeps that service's id as its `source`, so finding it again chooses the place
  already kept rather than a new one beside it. A place shared from Amap (Here (作为时刻的地点), or
  保存为地点 to only keep it) is followed from its short link and shifted from GCJ-02 back to WGS
  84.
- A place's page edits it (编辑地点) in the same picker, opened on the place as it is with no
  other place offered: the pin moved, the choice renamed or given another kind. It keeps its
  id, so its Moments, check-ins and bills stay at it. One of the phone's changes at once; one
  of the site's is a change under 改过的地点 until published, which the API takes the name,
  point and kind of, looking its division up again and routing its trips again when it moved.

New Moments and places live under the app's `local/` directory in the spec's layout, the
places with provisional ids (`p-<ulid>`), marked 未发布 until published.

## Publishing

Publishing needs an API: its address and token, set under 我 → 同步与来源 (the token in a file
backups leave out). Without them there is nothing to publish to: the card for it stays away,
and nothing is marked 未发布, on the timeline or on a Moment's page, until they are set. With
them the site's Moments can be changed and deleted too. Nothing reaches the site as it is
saved: new, changed and deleted Moments, changed places, public bills, trips and footprint
spans wait on the phone, past a restart. 发布更改, from the card at the top of 我, lists them,
shows what changed and takes a change back. 全部发布到网站 runs as WorkManager work in a foreground
service with a progress notification, waiting out a dropped link: it uploads the new
originals a few at a time, then sends everything else as one `POST /v2/changes`, which the
API takes whole or not at all. What is published leaves the phone's store for the site's.
取消发布, on the page or the notification, stops it while it uploads or waits: what was uploaded
stays for the next try, and a change set already on its way still goes through and is noted.
清除全部更改 takes back everything waiting, after asking: the Moments written on the phone are
deleted with their photos' copies, and the site's records, places, trips and bills go back
to what the site has. A public bill whose words or amount changed, and a footprint span,
stay to be published.

## Backup

备份, under 我 → 同步与来源, puts the phone's databases in a WebDAV directory of the owner's:
its address, user name and password are set in one dialog (the password in a file Android's
backup leaves out). `backup/BackupArchive` writes one zip, `here-backup-<yyyyMMdd-HHmmss>.zip`:
under `databases/` the ledger, the footprints and the copy of the API's records, each a
consistent copy made by SQLite's `VACUUM INTO`, with the footprints' reminders and companions;
under `local/` the unpublished Moments and places, without `local/media/`. Photos, videos and
the tokens stay out. 每天自动备份, on until turned off, backs up once a day as WorkManager's
periodic work on a network with the battery not low; 立即备份 backs up at once. After each
backup the directory keeps the newest seven and deletes the rest, leaving other files alone.
从备份恢复 lists the backups in the directory, newest first; restoring the one picked
downloads it, unpacks only what a backup holds into `noBackupFilesDir/restore/` and starts
the app again. As the app starts, before anything opens the databases,
`BackupArchive.restoreStaged` moves the ledger, the footprints, the copy, the footprints'
JSON and `local/` (its `media/` left in place) aside into `noBackupFilesDir/restore-replaced/`,
kept until the next restore, and puts the backup's files where they were; cut off halfway, it
carries on the next time. Settings, tokens and the WebDAV sign-in stay as they are.

## Updates

更新, under 我 → 同步与来源, keeps the app current from this repository's GitHub releases. 每天检查更新,
on until turned off, reads the feed of GitHub's releases (`releases.atom`, GitHub's page
rather than its API, whose limit without a token is soon used up behind a shared proxy's
address) once a day as WorkManager's periodic work on a network, and nothing of the phone's
goes with it; the first time a release is newer than the app, a notification says so and
opens the page. 检查更新 asks at once and says the version and when it last looked. A newer
release adds 安装 x.y.z, which downloads its APK, `here-x.y.z.apk` as the release workflow
names it, into the cache, checks that it is this app, the version it says and newer, signed
with the same key as the app installed, and hands it to the system's installer, which asks
before it installs. Until 安装未知应用 is allowed for the app, a sheet says why first and goes to
the system's settings.

## Importing

导入, under 我 (`moments://import`), is where every file from another app comes in, grouped by
where it goes: 时刻 (Swarm), 账本 (iCost) and 足迹 (GPX). A row opens the system's file picker
and what came in is said in a dialog; Swarm's progress shows on its row while it runs. An empty
ledger keeps its own button for iCost, and an iCost file shared in still opens 记账.

## Importing from Swarm

从 Swarm 导入, under 我 → 导入, reads the .zip Swarm (Foursquare) exports
(`data/swarm/Swarm.kt`): its `checkins*.json`, and `photos*.json` with the `pix/` they name;
the account, friends, devices, visits and comments in it are left alone. Each check-in comes
in private, at its time in the offset it was made at and its point as Swarm has it (WGS-84),
with a `checkin` part naming its venue and Swarm's ids. One with words or photos becomes a
Moment of the phone's, its words as written and its photos copied into `local/media/`; the
rest are entries of their check-in alone, a line 签到 <venue> in the timeline's records. Each
venue becomes a place of the phone's, kept on its own, named by its local name where Swarm keeps
one beside the English it exports, at its most accurate check-in's point, with the venue in its
`source`; a venue imported before keeps the place it has, the site's once published. The map
counts a place's check-ins with its Moments, and its sheet and page list them. Their
uids are made from the time and Swarm's id, so importing the same export again adds only what
is new and copies no photo twice. All of it waits with the other unpublished changes for
发布更改. Check a change to the importer against a real export with
`SWARM_EXPORT=<zip> ./gradlew :app:testDebugUnitTest --tests '*SwarmTest*'`, and never commit it.

## Sharing

A Moment's share sheet hands a copy of its words and a choice of photos to another app,
which leaves the recipient and the final send to the user. It never posts. It lists only
the apps on the phone; 准备图文 is always there. The sheet stays
open under each share, so the next one starts from it. A meal's copy
ends with a line of its place, each one's share of the bill and its verdict (好吃, 一般 or
踩雷), each behind an emoji, which can be edited away like the rest.

| Destination    | How                                                                                     |
| -------------- | --------------------------------------------------------------------------------------- |
| 发布到 X       | the X app's composer, up to four photos (stills of live photos, no video)              |
| 发布到 Bluesky | `bluesky://intent/compose?text=…` for text, else its share receiver, text and up to four photos |
| 发布到 B 站    | Bilibili's dynamic composer (`FollowingPublishActivity`) with text and photos          |
| 分享到 Telegram | Telegram's share receiver; with several photos the text is copied to paste as caption |
| 发布到 Threads | `barcelona://create?text=…` for text, else its share receiver with the text copied     |
| 发布到小红书   | its share receiver, the text copied; text alone opens 写想法                           |
| 发布到 Instagram | its feed post's `ShareHandlerActivity`, the text copied; needs a photo |
| 发布到抖音     | `SystemShareActivity` into the photo editor, the text copied; needs a photo            |
| 微信朋友圈     | `ShareToTimeLineUI` for text or one photo; more go through 准备图文                    |
| 准备图文       | the photos saved to a `Pictures/Here Share-…` album and the text copied                |

These entries depend on each app's version; when one fails, 准备图文 is offered with the
edited text and selection kept. Each share has its own cache directory, cleaned up after
a week, and the read grants given to the receiving apps are revoked with it.

The opt-in 分享与记账助手（试用） is an accessibility service
(`accessibility/ShareAccessibilityService`) that, for Telegram, Threads and Instagram only,
fills the composer (Telegram: finds the `@username` given and the caption; Instagram: the
caption, once the photos are edited and 继续 tapped by hand) and stops before sending. It
reads only the app of the share it was asked for, gives up after 40 seconds (Instagram: two
minutes) and keeps nothing. Each destination is an `AssistAdapter` with an `AssistFlow`
(`share/TelegramShareAdapter`, `share/ThreadsShareAdapter`, `share/InstagramShareAdapter`);
add an app the same way, with fixtures for its pages. The manifest keeps the
`share/TelegramShareService` name only so the permission already granted to it stays.

## Ledger

记账 is a ledger kept on the phone alone, in place of iCost: books of bills over accounts
in their own currencies. Its page (`moments://ledger`, from 我) has three tabs:

- **账单**, the book's bills under their months and days;
- **统计**, a month, a year or all of it: spending, earning and what is left, and charts
  drawn on a canvas (`ui/ledger/Charts.kt`) by day, by category and by weekday;
- **资产**, net assets, a line of the last twelve months, and the accounts by kind.

The book's name switches books or shows 全部账本, which the app opens on each time it
starts. The eye at the top of 我, and beside the ledger's month, turns on 演示模式 for showing
the app to others: every amount is hidden, an account goes by its kind alone (no name, no
bank's mark), and private Moments and trips are left out everywhere, the widgets included.

A bill is an expense, an income or a transfer (also a repayment or a loan), or a refund or
reimbursement written from its expense. `moments://new-bill`, or the ledger's +, opens one
on a keypad (sums like `12+3.4` add up). It has an amount in any currency, a category, one
account or several with their shares, its time and book, discounts and fees, whether it is
to be reimbursed or left out of the sums, and the Moment, place and trip it belongs to. A
bill with no place is placed by the footprints nearest its time (within a quarter of an
hour, good to 100 m), named through Photon from the point alone and kept in
`footprints.sqlite`; its + opens the new Moment's place picker on that point, and the place
kept or chosen there becomes the bill's. An account's 校准余额 sets its balance by moving its
opening balance.

The ledger meets the Moments: a Moment's page lists its bills, a meal with no bill offers
记一笔, a trip's and a day's page show what was spent, and 关联时刻和旅程 links meals and a
travel book's bills to their Moments and trips.

记账, under 我 (`moments://ledger-settings`), manages the books, the exchange rates, 自动记账 and
the data. A book opens as a sheet with its name, currency and categories (chips, each opening
a dialog to rename, add a second level, merge or delete it), archiving and deleting it; the
rates open as a sheet too, with the European Central Bank's brought up to date from its head.
An empty ledger imports from iCost on its own page.

The data:

- **从 iCost 导入**, in 我 → 导入, reads either iCost's backup (`.backup`, a zip of its SQLite
  database sealed with the password iCost itself uses) or the .xlsx of its 账单导出, picked,
  shared in or opened with the app. It shows what it found before writing and skips what is
  already there, so each newer file brings the ledger up to date. Tags become `#tag` in the
  note. The backup is the fuller one: `ledger/ICostBackup` takes its accounts with their
  kinds and balances, ties refunds, reimbursements and a transfer's discount to the bills
  iCost tied them to, and keeps a bill paid from several accounts as one. Every account
  iCost has, new or already here, opens where the bills iCost also has leave it at iCost's
  balance, so balance adjustments need no bill and bills kept only here move it on from
  there. The export has no balances, so its accounts are calibrated after, and its refunds
  and reimbursements are matched by amount and time. Accounts are matched by name, spaces at
  either end aside, and those of one name become one, archived only when all of them are
  hidden in iCost and counted in net assets when any of them is. Budgets, recurring bills,
  installments, savings plans, templates, places and photos aren't carried over.
- **Rates** are the European Central Bank's, bundled in `assets/ledger-rates.json` and
  refreshed from Frankfurter only when asked.
- **备份到文件** and **从备份恢复** write and read the whole ledger as JSON; **导出 CSV**
  writes the bills in iCost's columns.

`ledger/LedgerStore` keeps it in `noBackupFilesDir/ledger.sqlite`, outside sync and
Android's backup. Bills are private: 记录项与公开 (`moments://items`) makes a book's new bills
public (under the 记录项, while the ledger is on), and a bill's 公开 chip says otherwise for one. A public bill publishes only its
kind, amount, currency, category and note (`data/store/Outside.kt`). `ledger/ICostImport`,
`ledger/Accounting` and `ledger/Links` are plain Kotlin tested in `LedgerTest`, which also
reads a real export when `ICOST_EXPORT` names one and a real backup when `ICOST_BACKUP` does.

**自动记账（试用）**, switched on in 记账, has the same accessibility service read WeChat Pay's and
Alipay's pages. On one that says a payment went through, it offers a 记一笔 notification with
the amount, time, note and account it read; tapping it opens
`moments://payment?amount=…&time=…&note=…` with a category guessed from the books' category
names in the note, which also picks its book. WeChat's note is the payee and the 商品 row.
Nothing is written until saved, and a payment matching a kept bill within ten minutes isn't
offered. It never taps, types or keeps the page. `ledger/Payments` reads the page, with
fixtures in `PaymentsTest`; `ledger/AutoBill` keeps the switch and posts the notification.

**侧键**, in 我 (`moments://side-key`), is for one phone, the OPPO Find X10 Pro Max (model
`PMX110`, `SideKeys.supported`); any other phone has no 侧键 row. It gives the physical OPPO key's single, double and long
press each an action: payment capture, the flashlight, an app or a link, media and volume
keys, or a system action (back, home, recent apps, notifications, quick settings, lock).
None is set on the user's behalf. The double-press window (300 ms, 150–600) and the long
press (600 ms, 400–2000) can be changed; a single press waits out the window. The flashlight
toggles the rear flash without the camera, reading its state each press.

Under 场景 the same gestures can do something else in a scene: 付款页, a completed WeChat Pay or
Alipay payment page as the payment reader recognizes it, or any app added there, while it is in
front. A gesture a scene leaves at 跟随默认 runs its default action. Only a press whose gesture
some scene changes looks at what is in front, and nothing read is kept.

It needs Shizuku started and the share-and-ledger accessibility service on: a small Shizuku
UserService reads the input device advertising `BTN_TRIGGER_HAPPY32`, hosted by that service,
with no foreground service of its own. Turned off, it restores the system's own action (once
Shizuku is back, if it was lost); after a restart Shizuku has to be started again. Stop any
other side-key remapper first. The payment action reads the page still in front, which needs
the phone unlocked but not 自动记账, and opens the bill to confirm; one already saved is
reported instead. The settings and the original action stay out of Android's backup.
`sidekey/GestureDetector` and `SideKeyActions` have timing and validation tests.

## Footprints

The footprints are GPS tracks kept on the phone alone, in
`noBackupFilesDir/footprints.sqlite` (`footprints/FootprintStore`), outside sync and backup.
地图 → 足迹 opens a panel to record, pick a day or trip, export GPX and delete (importing is in
我 → 导入); 足迹记录 (`moments://footprint-settings`), from 我 or the panel's settings button, sets
how recording behaves.

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
- **Import.** 导入 GPX 轨迹, in 我 → 导入, reads a GPX the app exported (`footprints/TrackImport`), a line
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
  any, else by its speeds, and never against them (a walk can't go at a drive's pace); slow
  traffic and a car park's crawl stay part of the drive. The map draws a drive solid, a ride
  thinner, a walk dotted and a stay as a ring; 足迹 → 行程分段 lists them with distance, average
  and top speed, names a stay after one of the phone's own places within 300 m (no request
  leaves for it), and frames one on the map when tapped; a stay's + opens the same picker on
  its middle, named as a bill's spot is, to keep it as a place of the phone's, or with 保存为签到
  as a private check-in there too (`source.app` `phone`), from the stay's start to its end,
  its uid made from the start so keeping it again changes it; a check-in already within the
  stay, Swarm's or another, is that visit, and none is added.
- **Notification.** Its icon shows the state (recording, waiting for a fix, location off).
  On Android 16 and later it asks to be a Live Update; on ColorOS that also needs
  流体云显示实时活动, linked from 足迹记录 → 状态栏显示.
- **Diagnostics.** `noBackupFilesDir/footprint-diagnostics/` keeps two rotating JSONL files
  of 8 MiB, about two days, with each request, fix, filter decision and state change, never
  a coordinate, network name or token. 足迹记录 → 诊断 → 导出诊断日志 exports them with a replay of the
  latest day through the current filter.
- **Publishing.** A trip's page has 公开这段旅程的轨迹: at the next 全部发布到网站 its line goes
  out simplified to 30 m (`track_shares`), and the site draws the trip along it. The raw
  samples never leave the phone.

### Connection automation

足迹记录 → 连接自动控制 starts and pauses recording by connections the user picks, none by
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
  and every three hours. 热力图 is a square a day, as many weeks as fit, a year by months at
  four by four and a row of days as a strip, of the public Moments, the spending (shades only,
  never an amount) or how far the footprints went; it is chosen as the widget is added and
  again by tapping its name, as ColorOS's long press has no way to set a widget up again.
- **Video.** The viewer goes picture-in-picture when the app is left while a video plays.
  A photo that can't be fetched there says so, with 重试.
- **Language and appearance.** Always the phone's: Chinese, or English when the phone speaks
  it, and light or dark as the system is. The API and the media origin have no default and
  are set in 我 → 同步与来源; the map's styles default to `BuildConfig` and can be changed there
  too, beside the AMap and Tencent keys, which have none either and stays on the phone, out
  of the backup.
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

## Releasing

A release is a tag: pushing `android-v1.2.3` runs `.github/workflows/android-release.yml`,
which runs the unit tests, builds the release at version 1.2.3 (version code 10203, each
part kept under 100) and puts `here-1.2.3.apk` in a GitHub release of the same name, its
notes the commits since the last one. Other builds take their version from the newest such
tag, so one built here installs over the release on the phone. The app checks for these
releases itself (Updates), in the repository `site.config.json`'s `github` names; its
`android.applicationId` there is the app's id, while the code keeps the package
`cc.hyoban.moments`.

Every build is signed with the release key when there is one, debug builds too, so they install
over each other and keep the app's data; without it they take the machine's debug key. Locally
the key is in `local.properties` (`release.storeFile`, `release.storePassword`,
`release.keyAlias`, `release.keyPassword`); in Actions it is the repository's secrets
`ANDROID_RELEASE_KEYSTORE` (the keystore in base64), `ANDROID_RELEASE_STORE_PASSWORD`,
`ANDROID_RELEASE_KEY_ALIAS` and `ANDROID_RELEASE_KEY_PASSWORD`. The keystore itself stays out of
the repository, kept somewhere safe: a lost key can't sign an update to the app installed with it.

## On the phone, from adb

Every page has a link, `moments://moments`, `map`, `trips`, `settings` (我), `sync`,
`import`, `items`, `ledger-settings`, `footprint-settings`, `side-key`, `new`,
`moment/{uid}`, `trip/{id}`, `play/{id}`, `day/{YYYY-MM-DD}` and `place/{id}`, which `adb
shell am start -d` opens. Opening the app, a link's page comes over the tab it is under (a
trip or a place over 地图, a setting, the ledger or the changes over 我, the rest over 时间线), so
back, the top bar's or the system's, leads to that tab and the tabs go on from there; opened
while the app is on screen, it goes over the page shown. A page a widget opens stands alone
over the app, and back closes it onto the app as it was. Buttons, tabs and cards carry test
tags that `uiautomator dump` reports as resource ids (`tab_map`, `settings`, `back`,
`moment_{uid}`, `media_{uid}_{index}`, `trip_{id}`, `day_{date}`, …), and `scripts/ui.py`
uses both:

```sh
scripts/ui.py open moment/<uid>
scripts/ui.py dump
scripts/ui.py tap tab_map
```

## Layout

- `data/spec`: the records as Kotlin, each keeping the fields it doesn't know.
- `data/api`: the API's rows and writes.
- `data/copy`: the copy of the API's rows, in D1's tables.
- `data/store`: what the screens are given from the copy, and the phone's own notes.
- `data/local`: the Moments written on the phone, their time and place.
- `data/media`: photo, poster and video URLs, picked as the site picks them.
- `data/trips`: which trip a Moment is on, its days and stops, and its playback.
- `publish`: the publishing work.
- `ledger`, `footprints`, `share`, `accessibility`: the ledger, the footprints, sharing
  and the assistant.
- `widget`: the home screen widgets (Glance).
- `ui`: the Compose screens; `ui/design` and `ui/theme` the design system.

## Thanks

The cat on the icon is 馒头 (Mantou), drawn from a photo of the real one. The foot of 我
says so too, beside a photo of its face and the app's version.
