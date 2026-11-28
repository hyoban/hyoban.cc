# @hyoban/trip-map

A map of the places your posts were written at, and a player that replays your trips
along their routes, on [Mapbox GL JS](https://docs.mapbox.com/mapbox-gl-js/). Extracted
from [hyoban.cc](https://hyoban.cc/map/), whose `/map/` and `/trip/` pages are the
reference use.

```sh
npm install @hyoban/trip-map
```

ESM only. Needs a [Mapbox access token](https://docs.mapbox.com/help/getting-started/access-tokens/)
and a 2023-or-later browser (container queries, `:has()`, `dvh`; recording a video also
needs Chromium's Region Capture).

## Smallest page

```html
<link rel="stylesheet" href="node_modules/@hyoban/trip-map/dist/styles.css" />
<main data-trip-map style="position: relative; height: 100dvh">
  <div data-trip-map-canvas style="position: absolute; inset: 0"></div>
  <p data-trip-map-loading>Loading…</p>
  <section data-trip-map-fallback hidden>
    <p data-trip-map-fallback-message></p>
    <button data-trip-map-fallback-retry hidden>Retry</button>
  </section>
  <aside data-trip-map-drawer hidden>
    <p data-trip-map-drawer-eyebrow></p>
    <h2 data-trip-map-drawer-title></h2>
    <!-- Optional: a place's `detail`, hidden for one without. -->
    <p data-trip-map-drawer-detail hidden></p>
    <button data-trip-map-drawer-close>×</button>
    <div data-trip-map-drawer-content></div>
  </aside>
  <section data-trip-map-panel hidden></section>
  <div data-trip-map-stage></div>
  <dialog data-trip-map-shortcuts></dialog>
</main>
<script type="module">
  import { createTripMap } from '@hyoban/trip-map/client'

  createTripMap({
    mapboxToken: 'pk.…',
    page: document.querySelector('[data-trip-map]'),
    places: [
      {
        id: 'nanjing',
        name: '南京',
        latitude: 32.06,
        longitude: 118.8,
        count: 2,
        firstYear: 2026,
        months: ['2026-05', '2024-10'],
        years: [2026, 2024],
        color: '#3b82f6',
      },
    ],
    trips: [], // getTripSummary(trip) for each trip
    urls: {
      getPlaceUrl: (id) => `/places/${id}.html`, // an HTML fragment per place, or a file of several
      getTripDataUrl: (id) => `/trips/${id}.json`, // shapeTripData(trip, …) as JSON
    },
  })
</script>
```

The page's layout (where the drawer and trip panel sit, how they look) is yours. The package
renders the insides of the panel, the stage and the shortcut panel, and finds everything else
by its `data-trip-map-*` attribute.

Mapbox's CSS uses the `components` layer. If the site puts its reset in `base`, declare
`@layer base, components;` before loading the map stylesheet.

The drawer, the lists and the settings are drawers ([`@hyoban/drawer`](../drawer), bundled
in): they slide in from the right, or up from the bottom below 48rem, and swipe back the same
way. Mark a sheet's scrolling part `data-drawer-content` so a mouse selects text there instead
of dragging. `trip-map-toolbar__actions` on the toolbar's buttons moves them aside while one
is open.

## What's in it

| Entry                         | Runs in  | Holds                                                                                                                                                                                                     |
| ----------------------------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@hyoban/trip-map`            | anywhere | The model (`defineLocations`, `defineTrips`, `buildTrip`), its shaping for the page (`shapeTripData`, `getTripSummary`, `getPlaceMarkers`, `getMapStats`), the markup (`renderTripPanel`…) and the words. |
| `@hyoban/trip-map/client`     | browser  | `createTripMap` for the map page, and the pieces it is made of: `createTripPlayer`, `createTripOverview`, `captureMap`. Imports mapbox-gl; import it on the map page only.                                |
| `@hyoban/trip-map/dom`        | browser  | `createCountUp` (a number rolling up with number-flow) and `createIcon`, for your own pages.                                                                                                              |
| `@hyoban/trip-map/preview`    | Node     | `renderTripPreview`: a trip's route beside a cover photo as PNG bytes, with [sharp](https://sharp.pixelplumbing.com/) (an optional peer dependency).                                                      |
| `@hyoban/trip-map/styles.css` | browser  | Everything the map and the player put on the page, mapbox-gl's styles included.                                                                                                                           |

## From posts to a map

1. **Define places and trips.** Places are `{ latitude, longitude, name }` by id. A trip is a
   date range, with optional waypoints (places passed without a post), `straightLegs` (ferry,
   plane or train legs, drawn dashed) and `sameWayBack` legs.

   ```ts
   import { defineLocations, defineTrips } from '@hyoban/trip-map'

   export const locations = defineLocations({
     nanjing: { latitude: 32.0603, longitude: 118.7969, name: '南京' },
   })

   export const trips = defineTrips([
     { end: '2026-10-06', id: '2026-national-day', start: '2026-09-30', title: '国庆东北自驾' },
   ])
   ```

2. **Build each trip** from its posts (anything with `dateKey`, `locations` and `occurredAt`,
   oldest first):

   ```ts
   import { buildTrip, getLegKey, getTrainLegKey } from '@hyoban/trip-map'

   const trip = buildTrip(definition, posts, {
     resolveLocation: (id) => ({ id, ...locations[id] }),
     routes, // { [getLegKey(from, to)]: { coordinates, distance, duration } }, cached
   })
   ```

   Routes come from Valhalla (FOSSGIS's public server, no key) and OpenRailRouting for
   trains. Fetch them once and cache them; hyoban.cc's API (`apps/api/src/routes.ts`) shows
   how. A leg without a route is drawn straight.

3. **Shape the data.** `shapeTripData` turns a built trip into the `TripData` the player
   plays, adding what only you know about each stop. `getTripSummary` gives the overview each
   trip's route. `getPlaceMarkers` and `getMapStats` turn `groupMomentsByLocation`'s groups
   into the map's places and totals.

   ```ts
   const data = shapeTripData(trip, {
     covers: [{ src, width, height }], // the intro's photos; srcset and thumbnail are optional
     describeStop: (stop) => ({ anchor, href, images, text, time, video }),
     music: '/music/apple-cider.mp3',
     weather: (dateKey, place) => cache[getWeatherKey(dateKey, place)],
     driving: (dateKey) => odometer[dateKey], // { distanceKm, hours? }, for the day cards
     charging: (dateKey) => charges[dateKey], // { count, energyKwh }, for the day cards too
   })
   ```

   How places and routes are drawn:
   - A place with a `photo` (a square thumbnail about 96 pixels wide) is drawn as that photo,
     framed in white with its year's dot. Without one it is a balloon in its year's colour
     with its `kind`'s icon (`cafe`, `station`…, as `@hyoban/data-spec` names them) and its
     name below where there's room.
   - An open place's pin lifts off, larger, over a dot left on the spot with its name in bold.
   - Pins stay on their spot; crowded pins pile up there, busiest on top, as MapKit's do. Only
     a thick crowd gathers into a stack, counting its Moments, that opens as the map zooms in.
   - A trip summary's `color` colours its route; without one it uses the text colour. The
     settings' `routes` switch leaves only the route picked or pointed at.

   Serve each trip's data as JSON (`urls.getTripDataUrl`) and each place's posts as an HTML
   fragment (`urls.getPlaceUrl`). Several places can share a file as one
   `<template data-trip-map-place="<id>">` each (by default `/map/places/<getPlaceShard(id)>/`).
   Pass the places and trip summaries to `createTripMap`, or inline them as
   `<script type="application/json" data-trip-map-places>` (and `-trips`, `-stats`, and
   `-trip` for the trip the page opens on). `toScriptJson` makes JSON safe to inline.

4. **Render the page** (see [The map page](#the-map-page)), then on the client:

   ```ts
   import { createTripMap } from '@hyoban/trip-map/client'

   const map = createTripMap({ page, mapboxToken, … })
   map.destroy() // before the page is replaced
   ```

## The map page

Everything is found by attribute under the root `[data-trip-map]`:

| Attribute                                                                                                                       | Required | What it is                                                                                                                                                                               |
| ------------------------------------------------------------------------------------------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `data-trip-map-canvas`                                                                                                          | yes      | The map's container.                                                                                                                                                                     |
| `data-trip-map-loading`                                                                                                         | yes      | A line shown while the map loads.                                                                                                                                                        |
| `data-trip-map-placeholder`                                                                                                     | no       | A picture of the first view, over the canvas until the map loads, then faded out. See [Placeholder](#placeholder).                                                                       |
| `data-trip-map-fallback`, `-fallback-message`, `-fallback-retry`                                                                | yes      | Shown when the map can't load. Add a `[data-trip-map-place="<id>"]` link per place, to its map address, so places stay reachable.                                                        |
| `data-trip-map-drawer`, `-drawer-title`, `-drawer-eyebrow`, `-drawer-content`, `-drawer-close`                                  | yes      | The drawer a place opens with its posts.                                                                                                                                                 |
| `data-trip-map-drawer-detail`                                                                                                   | no       | A line in the drawer for the place's `detail` (what and where it is); hidden for a place without one.                                                                                    |
| `data-trip-map-panel`                                                                                                           | yes      | The trip player's side card. Filled by `renderTripPanel` (on the server, so a trip page opens on its intro card at once) or by the client when empty.                                    |
| `data-trip-map-stage`                                                                                                           | yes      | The presentation layout. Filled by `renderTripStage`, or by the client.                                                                                                                  |
| `data-trip-map-shortcuts`                                                                                                       | yes      | A `<dialog>` for the shortcut panel. Filled by `renderShortcuts`, or by the client.                                                                                                      |
| `data-trip-map-lightbox` (+ `-lightbox-close/previous/next`)                                                                    | no       | A `<dialog>` the drawer's photos open in, driven by your `createLightboxMedia`.                                                                                                          |
| `data-trip-map-toolbar`, `data-trip-map-legend`, `data-trip-map-screenshot`, `data-trip-map-trip-link="<id>"`                   | no       | A toolbar and a legend the player keeps clear of, a button that saves a screenshot, and links that open trips. See [Trip links](#trip-links).                                            |
| `data-trip-map-reset-view`, `data-trip-map-locate`                                                                              | no       | Buttons, enabled once the map loads: back to the opening view (every place, facing north), and to the viewer's location. See [Locate](#locate).                                          |
| `data-trip-map-fog`, `data-trip-map-fog-area`                                                                                   | no       | A fog-of-war toggle (button or checkbox), and where the lit cities are counted while it shows. Pressed or checked, a light fog covers the map, thinner over cities with more (`fogUrl`). |
| `dialog[data-trip-map-settings]`, `data-trip-map-settings-open`                                                                 | no       | The map's settings dialog and its openers. See [Settings](#settings).                                                                                                                    |
| `data-trip-map-trip-list` (+ `-trip-list-close`), `data-trip-map-trip-list-open`                                                | no       | The trip list beside the map, where the drawer sits, holding `data-trip-map-trip-link`s, and its opener. See [Trip list](#trip-list).                                                    |
| `data-trip-map-trip-details` (+ `-trip-details-close`, `-trip-details-title`, `-trip-details-eyebrow`, `-trip-details-content`) | no       | A trip's sidebar. See [Trip sidebar](#trip-sidebar).                                                                                                                                     |
| `data-trip-map-year="<year>"`, `data-trip-map-year-clear`, `data-trip-map-trip-year="<year>"`                                   | no       | Legend links that narrow the map to a year. See [Periods](#periods).                                                                                                                     |
| `data-trip-map-moments` (+ `-moments-close`, `-moments-title`, `-moments-eyebrow`, `-moments-content`)                          | no       | The picked period's Moments beside the map, under the drawer. See [Periods](#periods).                                                                                                   |
| `data-trip-map-trip-pick="<id>"`, `data-trip-map-stop-play="<id>"`, `data-trip-map-place-link="<id>"` in the fragments          | no       | Opens a trip's sidebar (`getPickedTripPath(id)`); plays a trip from the card `data-trip-map-stop` names; in the drawer, opens another place's drawer.                                    |
| `data-trip-map-token`                                                                                                           | no       | The Mapbox token, unless passed as an option.                                                                                                                                            |

### Placeholder

`getStaticMapUrl` gives a picture from the Mapbox Static Images API, at the camera
`getAllPlacesCamera` frames every place with. A trip's page (`/trip/<id>/`) instead opens flat
on the trip's route, framed by `getAllPlacesCamera` over its points no closer than
`TRIP_FRAME_MAX_ZOOM`. The page can then draw the route over the picture with
`projectOnCamera` exactly where the map will; the map leans back once loaded.

### Trip links

Outside the trip list, `data-trip-map-trip-link`s form one Tab stop, moved along with the
arrow keys, Home and End. Render all but the first with `tabindex="-1"`.

### Locate

The locate button asks the browser and puts a dot at the viewer's location. It is hidden
without geolocation, `aria-busy` while looking, and on failure its `title` says so
(`locateFailed`).

### Settings

Settings are remembered in the browser and applied at once. Until set, a device too slow for
Standard (a software renderer, 2 GB of memory or less, or a CPU about five times slower than a
fast laptop's) gets `classic`, and a map 48rem wide or less gets `mercator`.

The form scrolls under the header. Position visually hidden radios within their labels so
moving focus to a radio scrolls the form.

The form's fields, by name:

- Radios `style`: `standard`, `faded`, `monochrome` (Mapbox Standard in 3D in its colour
  themes) or `classic` (the flat light and dark map).
- Radios `light`: `auto` (by colour scheme), `time`, `day`, `dawn`, `dusk`, `night`. `time`
  follows the viewer's clock: dawn from 5, day from 7, dusk from 18, night from 20. It marks
  the page `data-trip-map-scheme` `dark` at dusk and night and `light` otherwise, for its styles
  to follow, and leaves the mark on when the map is destroyed so the next map takes over.
- Radios `projection`: `globe`, `mercator`.
- Checkboxes `buildings`, `roads`, `pois`, `transit`, `landmarks`, `routes` and `tilt`
  (leaning more the closer it is).

Fieldsets marked `data-trip-map-standard` are disabled in `classic`.
`data-trip-map-settings-close` closes the dialog; `data-trip-map-settings-reset` restores the
defaults.

### Trip list

The list is open while the address is the trip list's (`/trip/[<year>/[<month>/]]`); render it
without `hidden` on that page. Resting the pointer on a trip for two seconds, or reaching it by
keyboard, flies the map to its route and lifts it. Clicking it opens its sidebar.

### Trip sidebar

- Open while the address is `getPickedTripPath(id)` (`/trip/<year>/<rest>/` for an id starting
  with its year). Opened from its list row, its route on the map (clicking again plays it) or a
  Moment's trip link.
- Opening flies the map to the route, and only the trip's own places (its summary's `places`)
  stay on the map.
- `-trip-details-content` holds the fragment `getTripMomentsUrl(id)` gives, or already has it
  if its `data-trip-map-trip-details-trip` says so.
- A `[data-trip-map-stop-play="<id>"]` in it plays the trip from the card its
  `data-trip-map-stop` names.
- Reading it plays the trip along on the map: each `[data-trip-map-stop-entry="<anchor>"]` (a
  stop's Moment, in order) is arrived at as it scrolls into view.
- Closing returns to where it was opened from, camera included; opened on its own address, it
  returns to the list.

### Periods

A year link narrows the map to that year:

- Only places with Moments then stay; the others step back to gray dots. Only trips under way
  then stay, on the map and in the trip list, framed beside `data-trip-map-moments`.
- Pressing it again (or from one of its months), a `-year-clear` button, the Moments' close
  button or Esc shows everything again. The `-year-clear` button, and the `<li>` holding it,
  shows only while a period is picked.
- Each link points at the address pressing it goes to, kept current as the period changes.
  The picked one has `aria-current="true"`, and the legend has `data-trip-map-year-picked`
  meanwhile.
- Trip list rows outside the period are hidden, and so are their groups (`-trip-year`) once
  empty. Non-trip rows spanning two days, `data-trip-map-dates="<start>/<end>"`, hide the same
  way, as does a `data-trip-map-dates-group` once all its rows are hidden.
- The address names the period (`getMapPath(period)` on the map), and opening on one picks
  it: render that page already picked.

The Moments sidebar (`data-trip-map-moments`):

- `-moments-content` holds the fragment `getMomentsUrl(year)` gives for the year, or already
  has it if its `data-trip-map-moments-year` says so. With `data-trip-map-moments-month-only`
  it holds just that month, and the year is fetched once another month, or the whole year, is
  picked.
- The fragment has a `[data-trip-map-moments-month="<month>"]` per month, with
  `data-trip-map-count` Moments and a `[data-trip-map-moments-heading]` hidden while that month
  is picked.
- `[data-trip-map-month="<month>"]` links narrow the map to a month (`-month-all` to the whole
  year), each pointing at its address, the one shown with `aria-current="true"`.
- A `[data-trip-map-place-link="<id>"]` in it flies the map to that place.

In the drawer, the years in a place's line of totals narrow the map to them, keeping the place
in view.

### CJK labels

A worker draws CJK basemap labels from installed system fonts, rather than downloading
256-character glyph ranges or rasterizing on the main thread. Without worker OffscreenCanvas,
or if the worker fails, Mapbox's native local rasterizer is used. Standard asks Mapbox's glyph
service to compose the remaining fallback fonts in one request per font stack and range; a
custom style keeps client-side composition, for compatibility with its glyph service.

The adapter relies on Mapbox's internal glyph cache, so Mapbox GL JS and TinySDF are pinned to
versions verified together. Check glyph equivalence in the browser when updating them (see
[Development](#development)).

## Options

`createTripMap(options)` takes:

| Option                                                                 | Default                             | What it does                                                                                                                                                                                                                                                                                                                                                                  |
| ---------------------------------------------------------------------- | ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `page`                                                                 | required                            | The root element.                                                                                                                                                                                                                                                                                                                                                             |
| `mapboxToken`                                                          | `data-trip-map-token`               | The Mapbox access token.                                                                                                                                                                                                                                                                                                                                                      |
| `places`, `trips`, `trip`, `stats`                                     | the page's JSON                     | The places, every trip's summary, the trip the page opens on, the screenshot's totals.                                                                                                                                                                                                                                                                                        |
| `urls`                                                                 | `defaultUrls`                       | Any of them: what addresses mean, all in the path (`/map/[<year>/[<month>/]][place/<id>/]`, `/trip/[<year>/[<month>/]]` listing, `/trip/<year>/<rest>/` with a trip picked, `/trip/<year>/<rest>/play/[<stop>/]` playing), and where fragments and data are fetched from. One page can answer several addresses: the map reads the path (see the site's `public/_redirects`). |
| `messages`                                                             | Chinese                             | Any of the words below, and `locale`.                                                                                                                                                                                                                                                                                                                                         |
| `mapStyle`                                                             | Mapbox Standard, by day or by night | A style URL, or `{ light, dark }`; the page's settings take precedence.                                                                                                                                                                                                                                                                                                       |
| `mapLanguage`                                                          | from `locale`                       | The basemap's language, or `false` to leave it.                                                                                                                                                                                                                                                                                                                               |
| `music`                                                                | none                                | `{ src, title }[]` a video may be set to.                                                                                                                                                                                                                                                                                                                                     |
| `brand`                                                                | none                                | `{ name, image }` in the presentation's corner.                                                                                                                                                                                                                                                                                                                               |
| `carModel`                                                             | none                                | A glTF car (standing on y = 0, nose to +z, Meshopt allowed) that drives road stretches during flyover. Needs `three` installed.                                                                                                                                                                                                                                               |
| `siteLine`, `screenshotTitle`                                          | the host, the document title        | On a video's summary card; over a saved screenshot.                                                                                                                                                                                                                                                                                                                           |
| `onPeriodChange`                                                       | none                                | `(period) => {}` when the map is narrowed to a year, a month or none, for the site's own links to follow.                                                                                                                                                                                                                                                                     |
| `documentTitle`, `getDocumentTitle`, `tripListTitle`, `getPeriodTitle` | left alone                          | The document title with and without a trip playing or picked (`getDocumentTitle` is told which), with the trip list open (`tripListTitle` can be a function of its period), and with the map narrowed to a period.                                                                                                                                                            |
| `createLightboxMedia`                                                  | none                                | `(dialog, signal) => ({ show(item) })` for the drawer's photos.                                                                                                                                                                                                                                                                                                               |
| `fogUrl`, `fogRegionsUrl`                                              | `/map/fog.json`, `/map/cities.json` | `buildFogOfWar(regions, places)` as JSON, whose `weights` (each place's `weight`, e.g. its Moments) thin the fog over each city; and the regions, GeoJSON features with an `id` (`fogged` ones are never lit).                                                                                                                                                                |
| `onError`                                                              | none                                | Called when a place's posts, a trip's data or the map itself can't be loaded; the map carries on without it.                                                                                                                                                                                                                                                                  |

It returns `{ page, activeTripId, destroy() }`. One map per page: the player's shortcuts listen
on the window.

### Flyover

The player's ⛰ button (or V) flies over the route while it plays. It is off by default and
remembered in the browser; a trip played along from its sidebar flies over the same way.

- The camera following the car comes lower and leans further back, as far as Standard's
  terrain still rises (zoom 12). It faces from each drive's start to its end, turning slowly
  between drives rather than at every bend, and turns back north for whole-trip views.
- With a `carModel`, that car drives road stretches in 3D in place of the car icon, standing
  on the terrain and turned along the road. It is about 56 pixels long at zoom 9 and scales at
  half the map's zoom rate, between 40 and 160 pixels.
- Its lamps (the glowing materials) are on while the map is lit dark or its clock reads dusk
  or night (18 to 5), off otherwise.
- three.js and the model load only when the car first drives.

The map and the player draw at most 60 frames a second, except right after a resize, so the
map doesn't flicker.

## Words and dates

Every word the map and player show is a message, a template with `{slots}`. Pass any of them in
`messages` to replace it, and `locale` for date and number formatting. Dates travel as
`YYYY-MM-DD` keys and are formatted where shown.

<details>
<summary>All messages and their defaults</summary>

| Message                  | Default                                      |
| ------------------------ | -------------------------------------------- |
| `locale`                 | zh-CN                                        |
| `clusterLabel`           | {title}，共 {count} 条记录                   |
| `clusterName`            | {name}等                                     |
| `clusterNamedTitle`      | {name} {count} 个地点                        |
| `clusterPlaces`          | {count} 处                                   |
| `clusterTitle`           | {count} 个地点                               |
| `drawerFailed`           | 没能加载这个地点的记录。                     |
| `drawerLoading`          | 加载中…                                      |
| `fogLit`                 | 点亮 {cities} 座城市 {provinces} 个省份      |
| `mapAttribution`         | 显示或隐藏地图署名                           |
| `mapInitFailed`          | 浏览器无法初始化地图。                       |
| `mapLoadFailed`          | 地图资源加载失败。                           |
| `mapLoadTimeout`         | 地图加载超时。                               |
| `mapLoadingSlow`         | 地图加载得有点慢，再等等…                    |
| `mapLogo`                | Mapbox 主页                                  |
| `mapNoPlaces`            | 还没有带地点的记录。                         |
| `mapNoToken`             | 尚未配置 Mapbox Token。                      |
| `mapTitle`               | 地图，方向键平移，加减键缩放                 |
| `momentCount`            | {count} 条记录                               |
| `momentsEyebrow`         | {count} 条记录 {places} 个地点               |
| `momentsEyebrowNoPlaces` | {count} 条记录 都没有地点                    |
| `momentsFailed`          | 没能加载这段时间的记录。                     |
| `monthItem`              | {year} 年 {month} 月                         |
| `placeEyebrow`           | {count} 条记录 {years}                       |
| `placeLabel`             | {name}，{count} 条记录，首次记录于 {year} 年 |
| `screenshotRendering`    | 正在生成截图…                                |
| `statDriving`            | 自驾 {count} 公里                            |
| `statMoments`            | {count} 条记录                               |
| `statPlaces`             | {count} 个地点                               |
| `statTrips`              | {count} 段旅程                               |
| `tapAgainToPlay`         | 再点一次回放                                 |
| `tripDetailsFailed`      | 没能加载这段旅程的记录。                     |
| `yearItem`               | {year} 年                                    |
| `yearSeparator`          | 、                                           |
| `dayLabel`               | 第 {day} 天                                  |
| `intro`                  | 开头                                         |
| `placeSeparator`         | 、                                           |
| `playbackSpeed`          | 播放速度 {speed}×                            |
| `replay`                 | 重新播放                                     |
| `separator`              | (a space)                                    |
| `summary`                | 旅程回顾                                     |
| `tripMeta`               | {range} {days} 天 {moments} 条记录           |
| `unitDays`               | 天                                           |
| `unitDrivingHours`       | 小时车程                                     |
| `unitDrivingKm`          | 公里自驾                                     |
| `unitMoments`            | 条记录                                       |
| `unitPhotos`             | 张照片                                       |
| `unitPlaces`             | 个地点                                       |
| `videoTitle`             | 视频                                         |
| `viewDayLink`            | 查看当天记录                                 |
| `viewDayMoments`         | 查看 {date}的记录                            |
| `viewPhoto`              | 查看第 {index} 张照片                        |
| `cancel`                 | 取消                                         |
| `chaptersFile`           | {name}-章节.txt                              |
| `close`                  | 关闭                                         |
| `closePlayback`          | 退出旅程回放                                 |
| `exitPresentation`       | 退出放映                                     |
| `exportVideo`            | 导出视频                                     |
| `flyover`                | 飞越视角                                     |
| `landscape`              | 横屏 16:9                                    |
| `landscapeFile`          | 横屏                                         |
| `music`                  | 背景音乐                                     |
| `next`                   | 下一站                                       |
| `noMusic`                | 无音乐                                       |
| `pause`                  | 暂停                                         |
| `play`                   | 播放                                         |
| `portrait`               | 竖屏 9:16                                    |
| `portraitFile`           | 竖屏                                         |
| `present`                | 放映                                         |
| `presentStart`           | 开始放映                                     |
| `previous`               | 上一站                                       |
| `progress`               | 旅程进度                                     |
| `ratio`                  | 画面                                         |
| `recordStart`            | 开始录制                                     |
| `safeArea`               | 为社交平台留边                               |
| `withKey`                | {action}（{key}）                            |
| `keyEnd`                 | End                                          |
| `keyEscape`              | Esc                                          |
| `keyHome`                | Home                                         |
| `keyShift`               | Shift                                        |
| `keySpace`               | 空格                                         |
| `shortcutCards`          | 上一张 / 下一张卡片                          |
| `shortcutDays`           | 上一天 / 下一天                              |
| `shortcutEnds`           | 回到开头 / 跳到回顾                          |
| `shortcutEscape`         | 退出放映 / 关闭回放                          |
| `shortcutExport`         | 导出视频                                     |
| `shortcutFlyover`        | 飞越视角                                     |
| `shortcutFullscreen`     | 全屏放映                                     |
| `shortcutHelp`           | 显示快捷键                                   |
| `shortcutMusic`          | 背景音乐                                     |
| `shortcutPhotos`         | 上一张 / 下一张照片                          |
| `shortcutPlay`           | 播放 / 暂停                                  |
| `shortcutSpeed`          | 减速 / 加速                                  |
| `shortcuts`              | 快捷键                                       |
| `shortcutsPlayback`      | 播放                                         |
| `shortcutsPresenting`    | 放映与导出                                   |
| `shortcutsSpeedSound`    | 速度与声音                                   |
| `drivingDayHoursLabel`   | 开了 {km} 公里 {hours} 小时                  |
| `drivingDayLabel`        | 开了 {km} 公里                               |
| `chargingDayLabel`       | 充电 {count} 次 {kwh} kWh                    |
| `weatherClear`           | 晴                                           |
| `weatherDrizzle`         | 毛毛雨                                       |
| `weatherFog`             | 雾                                           |
| `weatherLabel`           | {kind} {min}–{max}°C                         |
| `weatherOvercast`        | 阴                                           |
| `weatherPartlyCloudy`    | 多云                                         |
| `weatherRain`            | 雨                                           |
| `weatherShowers`         | 阵雨                                         |
| `weatherSnow`            | 雪                                           |
| `weatherSnowShowers`     | 阵雪                                         |
| `weatherThunderstorm`    | 雷雨                                         |
| `locateFailed`           | 没能获取你的位置                             |
| `userLocation`           | 你的位置                                     |

</details>

## Styling

`styles.css` styles everything the client puts on the page, mapbox-gl's styles included. Icons
are inline SVG (Lucide's), so neither Tailwind nor an icon font is needed. The theme comes from
these custom properties:

```css
:root {
  --color-bg: #fdfdfc;
  --color-border: #e9e9e7;
  --color-inline: #f9f9f8;
  --color-tx: #21201c;
  --color-tx-2: #63635e;
  --color-tx-3: #73736c;
  --font-mono: ui-monospace, monospace; /* optional */
}
```

## Development

The package is a hyoban.cc workspace member, imported there from source; `vp check` and
`vp test` at the root cover it. `vp run build` here writes the published `dist/` (declaration
files, source maps and the standalone stylesheet). See [CHANGELOG.md](./CHANGELOG.md) for versions.

With `vp run dev` running, `vp run map:bench --scenarios glyphs --throttle 1 --dpr 2` compares
the worker's glyph pixels and metrics with Mapbox's rasterizer in Chrome at all four weights,
and checks that no new glyphs are drawn on the main thread.

## License

[MIT](./LICENSE). `dist` bundles [@hyoban/drawer](../drawer), a port of Base UI and shadcn/ui (both MIT), TinySDF (BSD 2-Clause), and mapbox-gl's stylesheet; their notices are in [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).
