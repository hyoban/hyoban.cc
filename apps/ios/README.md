# Here, Moments for iOS

The Android app's records on an iPhone: the site's Moments, places and trips, new Moments
written there, and what stays the phone's own, a ledger and GPS footprints. It is SwiftUI and
follows the same design as Android, the 「这里 App 设计系统」 and its page designs: its components
(in `Design/`), layout and type, with the tokens and icons of `packages/design-tokens`. What floats,
the tab bar capsule and the circles over pages and the map, is Liquid Glass. iOS 26.1 and later.

## Reading

As on Android, the build names no API: until an API's address and its token are set under
我 → 同步与来源 (the token in the Keychain, this device only), it asks nothing of one and
everything written stays on the phone. With them it keeps a copy of every row D1 has but the
trips' routes, in the same tables (`copy.db`, generated from `@hyoban/data-spec/tables` into
`Data/CopyTables.swift`), through the system's SQLite. Each start, each return to the app and
each pull down asks `/v2/rows` for the rows changed since; the routes come from `GET /v2/routes`
with their ETag. A new API drops the copy and reads everything again.

The pages read the entries as they are, a Moment being an entry with words (`Data/Spec.swift`),
and work the trips out from the collections and their marks (`Data/Trips.swift`). Every record
is kept as its JSON, so a field this version doesn't know goes through untouched.

## Pages

Three tabs, 时间线, 地图 and 我, in the design's floating capsule with the ink 记录 beside it; on
the timeline it slides away as the list scrolls down. Search opens from the timeline's circle and
the map's search capsule.

- **时间线**: a conversation with oneself, newest first. Each day opens with its date in a
  capsule; Moments are bubbles from the right with their photos; the day's bills, charges,
  devices and subscriptions come from the left in one records bubble; a banner marks a trip, or
  says 进行中 and which day it is. A Moment's card grows into its page (the zoom transition).
- **地图** (MapKit): the places in the colour of the year they were first visited, the trips'
  routes, public tracks and today's footprints; 迷雾地图 lays the site's fog over it, from
  `apps/web/src/data/boundaries/cities.json`, copied into the app as it builds. A place tapped
  shows its card with 路线 (the system's Maps); a trip chosen frames it and offers 回放.
- **我**: the changes waiting, this month's spending with the eye that hides amounts, 账单与资产,
  足迹, 东西 (设备, 订阅, 车) and the settings: 记录项与公开, 同步与来源, 记账 and 足迹记录.

A day, a place and a trip each have a page; a trip's has 回放旅程, which plays the trip back as
the site's player does, the route never still, a card per Moment stepped through by the toolbar,
a swipe or a keyboard's arrows and Space, and 编辑旅程 (name, days, private, covers).

## Writing and publishing

记录 (`moments://new`) takes words, up to nine photos and videos from the system's photo
picker, copied whole with their EXIF, a time (the earliest photo's, else now), a place (where
the first photo was taken: the known place within 2 km, else a new one named by MapKit; else
chosen from the known places, MapKit's search or a pin), 私有, and a meal. A video over 100 MB,
or other than MP4, MOV or WebM, is refused.

Nothing reaches the site as it is saved: new, changed and deleted entries, places and trips wait
in `local/changes.json`, with the originals beside it, past a restart. 发布更改 lists them, shows
what changed and takes a change back; 全部发布到网站 uploads the new originals three at a time,
then sends everything as one `POST /v2/changes`, with the ledger's public bills and the
footprints' public spans (`Data/Outside.swift`).

## The phone's own

- **记账** (`Ledger/`): books, accounts, categories and bills in `ledger.sqlite`, the same model
  as Android's, on a keypad (`12+3.4` adds up); 统计 draws with Swift Charts; a book's or a bill's
  公开 publishes only its kind, amount, currency, category and note. Backed up and restored as
  JSON, exported as CSV. iCost's imports and automatic bills are Android's alone for now.
- **足迹** (`Footprints/`): Core Location's live updates under a background activity session,
  which rests the receiver while the phone is still; every fresh fix in `footprints.sqlite`, the
  line broken at fixes over 50 m uncertain and gaps over 45 seconds. GPX out (raw, with accuracy
  and speed) and in. A trip's page makes its span public, simplified to 30 m.

Both files, the copy and the unpublished changes are under Application Support, excluded from
the system's backup.

## Building and running

Open `apps/ios/Here.xcodeproj` in Xcode 27, or from a shell:

```sh
xcodebuild -project Here.xcodeproj -scheme Here -destination 'platform=iOS Simulator,name=<device>' build
```

The project's files are a synchronized folder: a file added under `Here/` is in the target.
Generated, never edited by hand: `Design/Tokens.swift` (`vp run design:tokens`),
`Data/CopyTables.swift` (`vp run data:tables`) and `Assets.xcassets/Icons`
(`python3 packages/design-tokens/icons/swift.py`). Words are in `zh-Hans.lproj` and `en.lproj`.

Every page has a `moments://` link, as on Android (`moment/{uid}`, `trip/{id}`, `day/{date}`,
`place/{id}`, `new`, `new-bill`, `changes`, `sync`, `ledger`, …). Debug builds also take
launch arguments, for a stand-in API on the Mac and a page to open:

```sh
xcrun simctl launch booted cc.hyoban.moments -debugApi http://127.0.0.1:8787 -debugToken <test token> -debugOpen moments://map
```

## Not yet

Home screen widgets, sharing to particular apps, the WebDAV backup, iCost's imports and
automatic bills, and the footprints' moves and stays.
