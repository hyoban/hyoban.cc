# Here for Apple platforms

The start of Here on iPhone and iPad, in SwiftUI; the Mac's app is Compose Desktop, sharing the
Android app's code (`apps/android`). For now it is `HereKit`, a Swift package
of what those apps will share; the apps themselves come next, following the same design system
as the Android app (the 「Here App 设计系统」 and 「Here App 页面设计稿」 artifacts) and the same
data contract (`GET /v2/rows` into a copy of D1's tables, `POST /v2/changes` to publish).

## What HereKit holds

| File                         | What it is                                                                                 |
| ---------------------------- | ------------------------------------------------------------------------------------------ |
| `Generated/Tokens.swift`     | the design's colours, steps, corners, shadow, blur, sizes, opacities, text styles, springs |
| `Generated/AppIcons.swift`   | the design system's icons as path data, and `AppIcon.all`                                  |
| `Generated/CopyTables.swift` | the statements that make the tables the copy keeps the API's records in                    |
| `Generated/Modules.swift`    | the modules the owner turns on or off, with their plugins and tables                       |
| `AppIcon.swift`              | reads an icon's path data into SwiftUI paths, and `AppIconView` draws it                   |

Everything under `Generated/` is written from the repository's shared sources and never edited by
hand. From the repository root:

```sh
vp run design:tokens                          # Tokens.swift, beside Android's Tokens.kt
python3 packages/design-tokens/icons/swift.py # AppIcons.swift, from icons.py
vp run data:tables                            # CopyTables.swift and Modules.swift, from @hyoban/data-spec
```

`vp test` checks that the tokens, tables and modules are current; CI also regenerates the icons
and fails on a difference.

## Checking

With Xcode installed, from this folder:

```sh
swift test
xcodebuild -scheme HereKit -destination 'generic/platform=iOS Simulator' build
```

The tests make the copy's tables in SQLite, read a made-up record's worked-out columns back, and
check that every icon reads as a path inside its 24 grid.
