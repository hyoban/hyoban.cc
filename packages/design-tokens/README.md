# @hyoban/design-tokens

The design system's values for every platform the app is built on: colours in light and dark,
the 4dp steps and the gaps, corners, shadows, opacities, the 13 text styles (`tokens.json`)
and the springs (`motion.json`, which `tokens.json`'s format can't hold). `icons/icons.py`
draws the app's icon set on a 24 grid.

These files are the one source. `vp run design:tokens` at the root writes the Android app's
`ui/theme/Tokens.kt`, and `python3 packages/design-tokens/icons/kotlin.py` its
`ui/design/AppIcons.kt`; an iOS or web build will get a generator of its own beside them. Never
edit the generated files by hand.

`tokens.json` is the design-system artifact's `project/tokens.json` too, in the shape that page
reads: change it here, regenerate, then publish the same file to the artifact. The year colours
must stay the site's (`packages/trip-map/src/theme.ts`); `vp test` checks that, that the
generated code is current, and the contrast of text and controls in both themes.
