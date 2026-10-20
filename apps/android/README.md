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

Two home screen widgets read the same copy, so they draw offline: 那年今天, a Moment with
a photo from this day in an earlier year, or one picked at random when there is none,
the same all day; and 旅程, the trip under way, else the last one, else the next, with
one of its photos. Each comes in ColorOS's card sizes, a row, a square, a wide card with
the photo beside the words and a large one with the Moment's words whole, rounds as the
launcher rounds its own, opens its page when tapped, takes the colours the theme page
sets, and is drawn again after each sync and every three hours.

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

## On the phone, from adb

Every page has a link, `moments://moments`, `map`, `trips`, `settings`,
`moment/{uid}`, `trip/{id}`, `day/{YYYY-MM-DD}` and `place/{id}`, which
`adb shell am start -d` opens straight away, going over the page shown when the app is
already open. The buttons, tabs and cards carry test tags that `uiautomator dump`
reports as resource ids (`tab_map`, `settings`, `back`, `moment_{uid}`,
`media_{uid}_{index}`, `trip_{id}`, `day_{date}`, `year_{year}`, `locate`, …), and
`scripts/ui.py` uses both:

```sh
scripts/ui.py open moment/01M466J3KG95G3V62CZ1C7ZZBH
scripts/ui.py dump
scripts/ui.py tap tab_map
```

The same names serve TalkBack: a photo is read as what it is, where it is among its
Moment's and its description; a calendar day as its date and how many Moments it has;
the months and days heading a list as headings; the theme's choices as a group with the
one chosen.

## Layout

- `data/spec` — the records as Kotlin, each keeping the fields it doesn't know.
- `data/api` — the API's change feed, places and trips.
- `data/store` — the copy on the phone, and the repository that brings it up to date
  and gives the screens what the site shows (no hidden Moments).
- `data/media` — photo, poster and video URLs, picked as the site picks them.
- `data/trips` — which trip a Moment is on, a trip's days and its stops in order.
- `widget` — the home screen's widgets (Glance) and what they pick for the day.
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
