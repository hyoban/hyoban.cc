# 这里, Moments for Android

The app that shows the site's Moments, places and trips on the phone: the places on a
map, every Moment with its photos and videos, and each trip's route and days. It reads
the API's public endpoints (`apps/api`) and keeps a copy under the app's `api/`
directory in the layout `packages/data-spec` defines, so it opens straight away and
works offline; each start, and each pull down on a list, asks only for what changed
since. It doesn't edit anything yet.

On the home screen it is 这里 (here): Material 3's nine-lobed cookie shape in green on
pale mint, with the map's "you are here" pulse in it, as an adaptive icon with its own
monochrome layer for themed icons.

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
The same page sets the look as FlClash's theme page does: light, dark or the system's,
the seed colour (the system's accent, Apple's system colours or one's own by hex), how
much of it the scheme carries, and dark in pure black.

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

## Thanks

The way this app looks is learned from [FlClash](https://github.com/chen08209/FlClash).
Its pages showed how calm and clear an Android app can feel: rows gathered into soft
cards under small captions, a quiet note beneath each group, a theme page where every
choice is shown rather than named. This app grew up copying those habits, one page
after another, and is much better for it. Thank you to chen08209 and everyone who works
on FlClash for making something so carefully, and for making it open for others to learn
from. The settings page carries the same thanks, with a link back to it.
