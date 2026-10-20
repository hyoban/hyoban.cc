# Moments for Android

The app that shows the site's Moments, places and trips on the phone: the places on a
map, every Moment with its photos and videos, and each trip's route and days. It reads
the API's public endpoints (`apps/api`) and keeps a copy under the app's `api/`
directory in the layout `packages/data-spec` defines, so it opens straight away and
works offline; each start, and each pull down on a list, asks only for what changed
since. It doesn't edit anything yet.

## Building

Android Studio opens `apps/android` as a project. From a shell, with the Android SDK
in `local.properties` (`sdk.dir=…`) and a JDK 17 or newer:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The maps are OpenFreeMap's vector tiles drawn by MapLibre, which need no key.

The API, the origin the photos and videos come from and the map's light and dark styles
are the build's (`BuildConfig`) until changed on the settings page, opened from the
Moments tab's header; a new API drops the copy on the phone and reads everything again.

The unit tests round-trip every record under the repository's `data/` and the sample
bundle in `scripts/fixtures/bundle` through the app's models, so the app and the site
can't drift apart without a test noticing; they also check the media URLs against the
site's rules, which trip a Moment is on, and the sync against a stand-in for the API.

## Layout

- `data/spec` — the records as Kotlin, each keeping the fields it doesn't know.
- `data/api` — the API's change feed, places and trips.
- `data/store` — the copy on the phone, and the repository that brings it up to date
  and gives the screens what the site shows (no hidden Moments).
- `data/media` — photo, poster and video URLs, picked as the site picks them.
- `data/trips` — which trip a Moment is on, a trip's days and its stops in order.
- `ui` — Compose screens: the map, the Moments, a Moment's page, the trips, a trip, the settings.

The app's own `data/` directory from when it recorded Moments is left on the phone as
it was.
