# Working on the iOS app

These instructions extend the repository's [shared guide](../../AGENTS.md) for `apps/ios`. They
apply equally to Codex and Claude. Maintain durable iOS rules here and product behavior in
[README.md](README.md).

## The design system, in glass

- The app follows the 「这里 App 设计系统」 artifact and the 「这里 App 页面设计稿」 canvas, the
  same design as Android, not iOS's own design language: its components by their names (the
  floating tab bar capsule with the ink 记录 beside it, no top bar but floating circle buttons
  over a large title, `SettingsGroup` and `PlainRows` rather than grouped lists, bubbles for the
  timeline), its layout, spacing, type and colours. A change to what the app shows goes into the
  design first, as the Android guide says.
- The one iOS difference is the material: what floats (the tab bar capsule, the circle buttons,
  cards over the map, the viewer's bars) is Liquid Glass (`glassEffect`) in the design's shapes,
  in place of `surface` with its hairline and shadow. Sheets, keyboards, sharing, permissions
  and the status bar are the system's, as the design leaves them to the platform.
- Colours are `Tokens.*` by the design's names, spacing `Space.*`, corners `Radius.*`, text the
  six styles through `.textStyle(_:strong:)`, icons the design's set through `Icon`, never SF
  Symbols. Components live in `Design/`, named as the design system's cards and Android's `ui/design`.
- Use system frameworks over libraries: MapKit, Swift Charts, SQLite3, Core Location, PhotosUI.
  Add no package dependency without a reason.
- Behavior follows the Android app and the shared guide (publishing only through 发布更改, the
  privacy of the ledger and footprints, no default API). Where iOS can't do what Android does
  (side keys, the accessibility assistant), leave it out rather than imitate it.
- Strings go in `zh-Hans.lproj/Localizable.strings` and `en.lproj/Localizable.strings` by key,
  never Chinese inline in Swift.
- Swift 6 with the main actor as the default: data types that cross threads are `nonisolated`.

## Check every change in the simulator

- Build with `xcodebuild` and install with `xcrun simctl install`; look at the page in the
  simulator, light and dark (`xcrun simctl ui <device> appearance dark`), rather than trusting a build.
- Test against a stand-in API on the Mac, never the real one: `wrangler dev` with its own
  config, `--persist-to` directory and `.dev.vars` holding only a test token, its D1 filled from
  `packages/data-spec/test/fixtures/data/` (`buildImportSql`). Open pages with `-debugOpen`
  and the `moments://` links.
