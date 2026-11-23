# Here for Apple platforms

Here in SwiftUI: a native Mac app now, iPhone and iPad later.

- **The Mac app** tries what MapKit can do with the API's real records. It is one
  full-window map used like Apple's Maps (a floating panel with search and the chosen place's
  or trip's card), in Apple's own macOS design (system colours, SF Pro, SF Symbols, Liquid
  Glass), as drawn by the page canvas's Mac boards (M00, M09, M10) and the design system's
  Mac cards. It only reads, and holds nothing else.
- **iPhone and iPad** will follow the Android app's design system (the 「Here App 设计系统」
  and 「Here App 页面设计稿」 artifacts) and its data contract: `GET /v2/rows` into a copy of
  D1's tables.

The Compose Desktop Mac app in `apps/android` stays as it is beside this one.

## The package

| Target    | What it is                                                                                                                                                         |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `HereKit` | what every Apple app shares: the generated tokens, icons and tables, and the records (below)                                                                       |
| `HereMac` | the Mac app: the map (M09) on MapKit, its floating panel (search, recents), the place and trip cards (M10), the fog and years, and the settings window for the API |

### HereKit

| File                         | What it is                                                                                                                                        |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Generated/Tokens.swift`     | the design's colours, steps, corners, shadow, blur, sizes, opacities, text styles, springs                                                        |
| `Generated/AppIcons.swift`   | the design system's icons as path data, and `AppIcon.all`                                                                                         |
| `Generated/CopyTables.swift` | the statements that create the copy's tables                                                                                                      |
| `Generated/Modules.swift`    | the modules the owner turns on or off, with their plugins and tables                                                                              |
| `AppIcon.swift`              | reads an icon's path data into SwiftUI paths; `AppIconView` draws it                                                                              |
| `Data/`                      | the records (`Entry`, `Place`, `Trip`…, each keeping its whole JSON), the API client, the row copy (`CopyStore`), `HereStore` over it, media URLs |

`Generated/` is written from the repository's shared sources and never edited by hand; see
[design-tokens](../../packages/design-tokens/README.md) and
[data-spec](../../packages/data-spec/README.md) for the commands. `vp test` checks that
tokens, tables and modules are current; CI also regenerates the icons and fails on a
difference.

## The Mac app

Needs macOS 26 and Xcode 26 or later. From this folder:

```sh
scripts/mac-app.sh            # build/Here.app, a debug build
scripts/mac-app.sh --release  # an optimized one
open build/Here.app
```

The script builds the `HereMac` product with SwiftPM and wraps it into an app with its string
table (Chinese, English in `en.lproj`), the icon drawn from the Compose app's `here.svg`, and
an `Info.plist` whose id, API address and media origin come from `site.config.json`. It signs
the app ad hoc, for this Mac.

- **Records** are copied into `copy.sqlite` in `~/Library/Application Support/HereMac`, read
  only: nothing is recorded, edited or published here. The API token is an owner-only file in
  `secrets/` there, not the Keychain, which would prompt again after every ad-hoc-signed
  build.
- **The map** is MapKit's standard style. Years use the design's year colours, oldest first,
  as on the web and Android. The fog lifts over the same cities with the same weights
  (`apps/web/src/data/boundaries/cities.json`, bundled by the script). MapKit can't draw water
  over the fog, so the sea stays fogged, and the fog appears rather than sweeping in. SwiftUI's
  MapKit has no clustering, so close places are not merged.
- **Chrome**, as in Maps: the map runs under the toolbar, whose glass band fades in only
  while the pointer is near the top of the window, where it can be dragged (`TitlebarHover`).
  The toolbar holds only the panel toggle (⌃⌘S). Top right are a glass capsule with the fog
  and My Location, MapKit's zoom stepper and its compass (`MapButtons`).
- **The panel's search** finds trips by name and places by name or area, grouped; ↑↓ and ↩
  pick. Empty, it lists the last eight opened (user defaults, `map.recents`). A place's
  Directions opens Apple Maps; Share sends an Apple Maps link.
- **China's basemap** is in GCJ-02, and MapKit draws coordinates as given. Records stay in
  WGS 84; everything drawn in the mainland, Hong Kong and Macau (places, routes, fog cities,
  camera targets, what Directions and Share hand to Maps) is shifted first (`ChinaShift`),
  over the same area Android shifts (`gcj-area.json`, bundled by the script).
- **Settings** (⌘,) hold only the API address and token; until set, a note over the map
  opens them.

## Checking

```sh
swift test
xcodebuild -scheme HereKit -destination 'generic/platform=iOS Simulator' build
```

The tests create the copy's tables in SQLite, read made-up records back through the copy,
and check that every icon parses as a path inside its 24 grid.
