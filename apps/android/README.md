# Moments for Android

The app that records Moments, places and trips on the phone and exports them for the
site. It is the source of its own data: records live under the app's `data/` directory
in the layout `src/data/spec.ts` defines, a bundle goes to the computer for
`pnpm data:publish`, and the receipt comes back.

## Building

Android Studio opens `apps/android` as a project. From a shell, with the Android SDK
in `local.properties` (`sdk.dir=…`) and a JDK 17 or newer:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The unit tests round-trip every record under the repository's `data/` and the sample
bundle in `scripts/fixtures/bundle` through the app's models, so the app and the site
can't drift apart without a test noticing.

## Layout

- `data/spec` — the records as Kotlin, each keeping the fields it doesn't know.
- `data/store` — the files, and the in-memory index the screens observe.
- `data/exchange` — bundles out, receipts in, backups both ways.
- `platform` — location, the photo picker's files, media access.
- `ui` — Compose screens: the tabs, the editor, a Moment's page.
