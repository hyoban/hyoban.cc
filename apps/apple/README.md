# Here for Apple platforms

Here in SwiftUI: a native Mac app now, iPhone and iPad to come. The Mac app is for trying what
MapKit can do with the API's real records: one full-window map, used as Apple's Maps is (a floating
panel with search and the chosen place's or trip's card), in Apple's own macOS design (system
colours, SF Pro, SF Symbols, Liquid Glass), as the page canvas's Mac boards (M00, M09, M10) and the
design system's Mac cards draw it; it only reads, and nothing else lives in it. iPhone and iPad are
to follow the same design system as the Android app (the 「Here App 设计系统」 and 「Here App
页面设计稿」 artifacts) and the same data contract: `GET /v2/rows` into a copy of D1's tables. The
Compose Desktop Mac app in `apps/android` stays as it is beside this one.

## The package

| Target    | What it is                                                                                                                                                         |
| --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `HereKit` | what every Apple app shares: the generated tokens, icons and tables, and the records (below)                                                                       |
| `HereMac` | the Mac app: the map (M09) on MapKit, its floating panel (search, recents), the place and trip cards (M10), the fog and years, and the settings window for the API |

### HereKit

| File                         | What it is                                                                                                                                                                                         |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Generated/Tokens.swift`     | the design's colours, steps, corners, shadow, blur, sizes, opacities, text styles, springs                                                                                                         |
| `Generated/AppIcons.swift`   | the design system's icons as path data, and `AppIcon.all`                                                                                                                                          |
| `Generated/CopyTables.swift` | the statements that make the tables the copy keeps the API's records in                                                                                                                            |
| `Generated/Modules.swift`    | the modules the owner turns on or off, with their plugins and tables                                                                                                                               |
| `AppIcon.swift`              | reads an icon's path data into SwiftUI paths, and `AppIconView` draws it                                                                                                                           |
| `Data/`                      | the records (`Entry`, `Place`, `Trip`…, each keeping its whole JSON), the API, the copy of D1's rows (`CopyStore`), `HereStore` that reads it for the pages, and media URLs as the site picks them |

Everything under `Generated/` is written from the repository's shared sources and never edited by
hand. From the repository root:

```sh
vp run design:tokens                          # Tokens.swift, beside Android's Tokens.kt
python3 packages/design-tokens/icons/swift.py # AppIcons.swift, from icons.py
vp run data:tables                            # CopyTables.swift and Modules.swift, from @hyoban/data-spec
```

`vp test` checks that the tokens, tables and modules are current; CI also regenerates the icons
and fails on a difference.

## The Mac app

It needs macOS 26 or later and Xcode 26 or later. From this folder:

```sh
scripts/mac-app.sh            # build/Here.app, a debug build
scripts/mac-app.sh --release  # an optimized one
open build/Here.app
```

The script builds the `HereMac` product with SwiftPM and wraps it into an app: its string table
(Chinese, and English in `en.lproj`), the icon drawn from the Compose app's `here.svg`, and an
`Info.plist` whose id, API address and media origin come from `site.config.json`. It signs
the app ad hoc, for this Mac.

What it does and keeps:

- **Records.** The API's rows are copied into `copy.sqlite` in `~/Library/Application Support/HereMac`.
  The Mac only reads them: nothing is recorded, edited or published here. The API's token is a file
  only the owner can read in `secrets/` there, not the Keychain, which an app signed ad hoc on each
  build would ask for again every time.
- **The map** is MapKit's. Years are coloured with the design's year colours, oldest first, as on the
  web and Android, and the fog lifts over the same cities (`apps/web/src/data/boundaries/cities.json`,
  which the script puts in the app) with the same weights. MapKit can't draw the water back over the
  fog, so on the Mac the sea stays fogged, and the fog appears rather than sweeping in. As in Maps,
  the map runs up under the toolbar, whose glass band fades in only while the pointer is over the top
  of the window, where it can be dragged (`TitlebarHover`); the toolbar holds only the panel's toggle (⌃⌘S); down the top right, as in Maps, are a glass
  capsule with the fog and My Location, MapKit's zoom stepper and its compass (`MapButtons`). The map
  keeps the standard style. The panel's search finds trips by name and places
  by name or area, grouped, ↑↓ and ↩ to pick; empty, it lists the last eight opened (user defaults,
  `map.recents`). A place's Directions opens Apple Maps; Share sends an Apple Maps link. SwiftUI's
  MapKit has no clustering, so close places are not merged.
- **China's basemap** is drawn in GCJ-02, and MapKit takes the coordinates it is given as they are.
  The records stay in WGS 84; what the map draws in the mainland, Hong Kong and Macau (places,
  routes, the cities the fog lifts, the camera's targets, what Directions and Share hand to Maps)
  is shifted onto it first (`ChinaShift`), over the area Android shifts by (`gcj-area.json`,
  which the script puts in the app).
- **Settings** (⌘,) hold only the API's address and token; until they are set, a note over the map
  opens them.

## Checking

```sh
swift test
xcodebuild -scheme HereKit -destination 'generic/platform=iOS Simulator' build
```

The tests make the copy's tables in SQLite, read made-up records back through the copy, and check that every icon reads as a path inside its 24 grid.
