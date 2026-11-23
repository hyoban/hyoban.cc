# @hyoban/design-tokens

The single source of the design system's values for every platform:

- `tokens.json`: colours in light and dark, the 4dp steps and gaps, corners, shadows,
  opacities and the six text styles.
- `motion.json`: the springs, which `tokens.json`'s format can't hold.
- `icons/icons.py`: the app's icon set, drawn on a 24 grid. `icons/svg.py` holds what both
  icon generators share.
- `stickers/stickers.py`: 打码's stickers in the icons' hand, in colour. A face's sit on the
  24 grid, a license plate's long animals on a 48 by 18 one, each with the box it covers.

Generators, run from the repository root:

| Command                                             | Writes                                                                             |
| --------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `vp run design:tokens`                              | Android's `ui/theme/Tokens.kt` and `apps/apple`'s `HereKit/Generated/Tokens.swift` |
| `python3 packages/design-tokens/icons/kotlin.py`    | Android's `ui/design/AppIcons.kt`                                                  |
| `python3 packages/design-tokens/icons/swift.py`     | Apple's `AppIcons.swift`                                                           |
| `python3 packages/design-tokens/stickers/kotlin.py` | Android's `ui/design/Stickers.kt`                                                  |

A web build will get its own generator beside them. Never edit generated files by hand.

`tokens.json` is also the design-system artifact's `project/tokens.json`, in the shape that
page reads: change it here, regenerate, then publish the same file to the artifact. The year
colours must match the site's (`packages/trip-map/src/theme.ts`). `vp test` checks that, that
generated code is current, and the contrast of text and controls in both themes.
