# @hyoban/trip-map

A map of the places your posts were written at, and a player that replays your trips
along their routes, on [Mapbox GL JS](https://docs.mapbox.com/mapbox-gl-js/). Extracted
from [hyoban.cc](https://hyoban.cc/map/), whose `/map/` and `/trip/<id>/` pages are the
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

The page's layout (where the drawer and the trip panel sit, what they look like) is
yours; the package renders the panel's, the stage's and the shortcut panel's insides,
and finds everything else by its `data-trip-map-*` attribute. The drawer, the lists and the settings are drawers ([`@hyoban/drawer`](../drawer), bundled in):
they slide in from the right, or up from the bottom below 48rem, and are swiped back that way;
mark a sheet's scrolling part `data-drawer-content` so a mouse selects text there rather than
dragging. `trip-map-toolbar__actions` on the toolbar's buttons moves them aside while one is open.

## What's in it

| Entry                         | Runs in  | Holds                                                                                                                                                                                                     |
| ----------------------------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@hyoban/trip-map`            | anywhere | The model (`defineLocations`, `defineTrips`, `buildTrip`), its shaping for the page (`shapeTripData`, `getTripSummary`, `getPlaceMarkers`, `getMapStats`), the markup (`renderTripPanel`…) and the words. |
| `@hyoban/trip-map/client`     | browser  | `createTripMap` for the map page, and the pieces it is made of: `createTripPlayer`, `createTripOverview`, `captureMap`. Imports mapbox-gl; import it on the map page only.                                |
| `@hyoban/trip-map/dom`        | browser  | `createCountUp` (a number rolling up with number-flow) and `createIcon`, for your own pages.                                                                                                              |
| `@hyoban/trip-map/preview`    | Node     | `renderTripPreview`: a trip's route beside a cover photo as PNG bytes, with [sharp](https://sharp.pixelplumbing.com/) (an optional peer dependency).                                                      |
| `@hyoban/trip-map/styles.css` | browser  | Everything the map and the player put on the page, mapbox-gl's styles included.                                                                                                                           |

## From posts to a map

1. **Define places and trips.** Places are `{ latitude, longitude, name }` by id; a trip is
   a date range, with optional waypoints (places passed without a post), `straightLegs`
   (ferry, plane or train legs, drawn dashed) and `sameWayBack` legs.

   ```ts
   import { defineLocations, defineTrips } from '@hyoban/trip-map'

   export const locations = defineLocations({
     nanjing: { latitude: 32.0603, longitude: 118.7969, name: '南京' },
   })

   export const trips = defineTrips([
     { end: '2026-10-06', id: '2026-national-day', start: '2026-09-30', title: '国庆东北自驾' },
   ])
   ```

2. **Build each trip** from its posts (anything with `dateKey`, `locations` and
   `occurredAt`, oldest first):

   ```ts
   import { buildTrip, getLegKey, getTrainLegKey } from '@hyoban/trip-map'

   const trip = buildTrip(definition, posts, {
     resolveLocation: (id) => ({ id, ...locations[id] }),
     routes, // { [getLegKey(from, to)]: { coordinates, distance, duration } }, cached
   })
   ```

   Routes come from the Mapbox Directions API (and OpenRailRouting for trains); fetch
   them once and cache them (hyoban.cc's API, `apps/api/src/routes.ts`, shows how).
   Without one a leg is drawn straight.

3. **Shape the data.** `shapeTripData` turns a built trip into the `TripData` the player
   plays, with what only you know about each stop; `getTripSummary` gives the overview
   every trip's route; `getPlaceMarkers` and `getMapStats` turn
   `groupMomentsByLocation`'s groups into the map's places and totals.

   ```ts
   const data = shapeTripData(trip, {
     covers: [{ src, width, height }], // the intro's photos; srcset and thumbnail are optional
     describeStop: (stop) => ({ anchor, href, images, text, time, video }),
     music: '/music/apple-cider.mp3',
     weather: (dateKey, place) => cache[getWeatherKey(dateKey, place)],
     driving: (dateKey) => odometer[dateKey], // { distanceKm, hours? }, for the day cards
   })
   ```

   Serve each trip's data as JSON (`urls.getTripDataUrl`), each place's posts as an HTML
   fragment (`urls.getPlaceUrl`), or several places to a file as a
   `<template data-trip-map-place="<id>">` each (by default `/map/places/<getPlaceShard(id)>/`), and pass the places and trip summaries to `createTripMap`
   or inline them in the page as `<script type="application/json" data-trip-map-places>`
   (and `-trips`, `-stats`, `-trip` for the trip the page opens on). `toScriptJson` makes
   JSON safe to inline.

4. **Render the page** (see [The map page](#the-map-page)), then on the client:

   ```ts
   import { createTripMap } from '@hyoban/trip-map/client'

   const map = createTripMap({ page, mapboxToken, … })
   map.destroy() // before the page is replaced
   ```

## The map page

Everything is found by attribute on the root `[data-trip-map]`:

| Attribute                                                                                                                       | Required | What it is                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `data-trip-map-canvas`                                                                                                          | yes      | The map's container.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `data-trip-map-loading`                                                                                                         | yes      | A line shown while the map loads.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `data-trip-map-placeholder`                                                                                                     | no       | A picture of the map's first view, over the canvas until the map has loaded, when it fades out. `getStaticMapUrl` gives one from the Mapbox Static Images API, at the camera `getAllPlacesCamera` frames every place with.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `data-trip-map-fallback`, `-fallback-message`, `-fallback-retry`                                                                | yes      | What's shown when the map can't load; add a `[data-trip-map-place="<id>"]` button per place to still reach them.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `data-trip-map-drawer`, `-drawer-title`, `-drawer-eyebrow`, `-drawer-content`, `-drawer-close`                                  | yes      | The drawer a place opens with its posts.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `data-trip-map-drawer-detail`                                                                                                   | no       | A line in the drawer for the place's `detail`, what and where it is; hidden for a place without one.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `data-trip-map-panel`                                                                                                           | yes      | The trip player's side card. Filled by `renderTripPanel` (on the server, so a trip page opens on its intro card at once) or by the client when empty.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `data-trip-map-stage`                                                                                                           | yes      | The presentation layout. Filled by `renderTripStage`, or by the client.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `data-trip-map-shortcuts`                                                                                                       | yes      | A `<dialog>` for the shortcut panel. Filled by `renderShortcuts`, or by the client.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `data-trip-map-lightbox` (+ `-lightbox-close/previous/next`)                                                                    | no       | A `<dialog>` the drawer's photos open in, driven by your `createLightboxMedia`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `data-trip-map-toolbar`, `data-trip-map-legend`, `data-trip-map-screenshot`, `data-trip-map-trip-link="<id>"`                   | no       | Things the player keeps clear of or wires up: a toolbar, a legend, a button that saves a screenshot, links that open trips.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `data-trip-map-reset-view`, `data-trip-map-locate`                                                                              | no       | Buttons that move the map, enabled once it has loaded: back to the view it opened on (every place shown, facing north), and to where the viewer is, asking the browser, with a dot there. The locate button is hidden without geolocation; while looking it's `aria-busy`, and if it can't, its `title` says so (`locateFailed`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `data-trip-map-fog`, `data-trip-map-fog-area`                                                                                   | no       | A toggle for the fog of war, a button or a checkbox (pressed or checked, it lays a light fog over the map, thinner over a city the more it holds; see `fogUrl`), and where the cities lit are counted while it shows.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `dialog[data-trip-map-settings]`, `data-trip-map-settings-open`                                                                 | no       | The map's settings, a dialog its openers show, remembered in the browser and shown on the map at once. Until they're set, a device too slow for Standard (a software renderer, 2 GB of memory or less, or a CPU about five times slower than a fast laptop's) gets `classic`, and a phone-wide map (48rem or less) `mercator`. Its form's fields, by name: radios `style` (`standard`, `faded`, `monochrome` — Mapbox Standard in 3D in its colour themes — or `classic`, the flat light and dark map), `light` (`auto` by the colour scheme; `time` by the viewer's clock, dawn from 5, day from 7, dusk from 18 and night from 20, the page marked `data-trip-map-scheme` `dark` at dusk and by night and `light` otherwise for its styles to follow, left on it as the map is destroyed for the next one to take over; `day`, `dawn`, `dusk`, `night`) and `projection` (`globe`, `mercator`); checkboxes `buildings`, `roads`, `pois`, `transit`, `landmarks` and `tilt` (leaning the closer it is). Fieldsets marked `data-trip-map-standard` are disabled in `classic`; `data-trip-map-settings-close` and `data-trip-map-settings-reset` close it and go back to the defaults. |
| `data-trip-map-trip-list` (+ `-trip-list-close`), `data-trip-map-trip-list-open`                                                | no       | A list of the trips beside the map, where the drawer sits, holding their `data-trip-map-trip-link`s, and what opens it. It's open while the address is `tripListPath`; render it without `hidden` on that page. Resting the pointer on a trip there for two seconds, or reaching it by keyboard, flies the map to its route and lifts it; clicking it opens its sidebar.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `data-trip-map-trip-details` (+ `-trip-details-close`, `-trip-details-title`, `-trip-details-eyebrow`, `-trip-details-content`) | no       | A trip's sidebar, open while the address is `getPickedTripPath(id)` (`/trip/<id>/`): opened from its row in the list, its route on the map (clicking it again plays it), or a Moment's trip link, it flies the map to the route. `-trip-details-content` holds the fragment `getTripMomentsUrl(id)` gives, or already has it if its `data-trip-map-trip-details-trip` says so; a `[data-trip-map-stop-play="<id>"]` in it plays the trip, from the card its `data-trip-map-stop` names, and reading it plays the trip along on the map as the player draws it, each `[data-trip-map-stop-entry="<anchor>"]` (a stop's Moment, in order) arrived at as it's scrolled to. Closing it goes back to where it was opened from, the camera too; opened on its own address, to the list.                                                                                                                                                                                                                                                                                                                                                                                                     |
| `data-trip-map-year="<year>"`, `data-trip-map-year-clear`, `data-trip-map-trip-year="<year>"`                                   | no       | Buttons, in the legend, that narrow the map to a year: only the places with Moments then, and the trips under way then, on it and in the trip list, framed beside `data-trip-map-moments`. Pressed again (or from one of its months), a `-year-clear` button (shown, with the `<li>` holding it, while a period is picked), the Moments' close button or Esc, everything again. The pressed one has `aria-pressed="true"`, and the legend `data-trip-map-year-picked` meanwhile; the trip list's rows, and its groups (`-trip-year`) once empty, are hidden. The address says the period picked, `getMapPath(period)` on the map, and opening on one picks it: render that page already picked.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `data-trip-map-moments` (+ `-moments-close`, `-moments-title`, `-moments-eyebrow`, `-moments-content`)                          | no       | The Moments of the period picked beside the map, under the drawer: the fragment `getMomentsUrl(year)` gives for its year, which `-moments-content` holds already if its `data-trip-map-moments-year` says so, or just one month of it if `data-trip-map-moments-month-only` says which (the year is fetched once another month, or the whole year, is picked). The fragment has a `[data-trip-map-moments-month="<month>"]` per month, with `data-trip-map-count` Moments and a `[data-trip-map-moments-heading]` hidden while it's picked, and `[data-trip-map-month="<month>"]` buttons narrowing the map to one (`-month-all` to the whole year); a `[data-trip-map-place-link="<id>"]` in it flies the map to that place.                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `data-trip-map-trip-pick="<id>"`, `data-trip-map-stop-play="<id>"`, `data-trip-map-place-link="<id>"` in the fragments          | no       | A trip's link, opening its sidebar (`getPickedTripPath(id)`); playing it, from the card `data-trip-map-stop` names; in the drawer, another place's, opening its drawer. The drawer's years in its line of totals narrow the map to them, keeping the place in view.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `data-trip-map-token`                                                                                                           | no       | The Mapbox token, unless passed as an option.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |

## Options

`createTripMap(options)` takes:

| Option                                                                 | Default                             | What it does                                                                                                                                                                                                                                                                                                                   |
| ---------------------------------------------------------------------- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `page`                                                                 | required                            | The root element.                                                                                                                                                                                                                                                                                                              |
| `mapboxToken`                                                          | `data-trip-map-token`               | The Mapbox access token.                                                                                                                                                                                                                                                                                                       |
| `places`, `trips`, `trip`, `stats`                                     | the page's JSON                     | The places, every trip's summary, the trip the page opens on, the screenshot's totals.                                                                                                                                                                                                                                         |
| `urls`                                                                 | `defaultUrls`                       | Any of them: what the addresses mean, all in the path (`/map/[<year>/[<month>/]][place/<id>/]`, `/trip/[<id>/]` with a trip picked, `/trip/<id>/play/[<stop>/]` playing), and where the fragments and data are fetched from. One page can answer several of them: the map reads the path (see the site's `public/_redirects`). |
| `messages`                                                             | Chinese                             | Any of the words below, and `locale`.                                                                                                                                                                                                                                                                                          |
| `mapStyle`                                                             | Mapbox Standard, by day or by night | A style URL, or `{ light, dark }`; settings set in the page's sidebar go first.                                                                                                                                                                                                                                                |
| `mapLanguage`                                                          | from `locale`                       | The basemap's language, or `false` to leave it.                                                                                                                                                                                                                                                                                |
| `music`                                                                | none                                | `{ src, title }[]` a video may be set to.                                                                                                                                                                                                                                                                                      |
| `brand`                                                                | none                                | `{ name, image }` on the presentation's corner.                                                                                                                                                                                                                                                                                |
| `siteLine`, `screenshotTitle`                                          | the host, the document title        | On a video's summary card; over a saved screenshot.                                                                                                                                                                                                                                                                            |
| `onPeriodChange`                                                       | none                                | `(period) => {}` as the map is narrowed to a year or a month, or to none: for links of the site's to follow.                                                                                                                                                                                                                   |
| `documentTitle`, `getDocumentTitle`, `tripListTitle`, `getPeriodTitle` | left alone                          | The document's title with and without a trip playing or picked (`getDocumentTitle` is told which), with the trip list open (`tripListTitle` can be a function of its period), and with the map narrowed to a period.                                                                                                           |
| `createLightboxMedia`                                                  | none                                | `(dialog, signal) => ({ show(item) })` for the drawer's photos.                                                                                                                                                                                                                                                                |
| `fogUrl`, `fogRegionsUrl`                                              | `/map/fog.json`, `/map/cities.json` | `buildFogOfWar(regions, places)` as JSON, its `weights` (each place's `weight`, say its Moments) thinning the fog over each city, and the regions: GeoJSON features with an `id` (`fogged` never lit).                                                                                                                         |
| `onError`                                                              | none                                | Told when a place's posts, a trip's data or the map itself couldn't be had; the map carries on without it.                                                                                                                                                                                                                     |

It returns `{ page, activeTripId, destroy() }`. One map per page: the player's shortcuts
listen on the window.

## Words and dates

Every word the map and the player show is a message, a template with `{slots}`; pass
any of them in `messages` to replace it, and `locale` for how dates and numbers are
formatted. Dates travel as `YYYY-MM-DD` keys and are formatted where they are shown.

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
| `fogLit`                 | 点亮 {cities} 座城市 · {provinces} 个省份    |
| `mapInitFailed`          | 浏览器无法初始化地图。                       |
| `mapLoadFailed`          | 地图资源加载失败。                           |
| `mapLoadTimeout`         | 地图加载超时。                               |
| `mapLoadingSlow`         | 地图加载得有点慢，再等等…                    |
| `mapNoPlaces`            | 还没有带地点的记录。                         |
| `mapNoToken`             | 尚未配置 Mapbox Token。                      |
| `momentCount`            | {count} 条记录                               |
| `momentsEyebrow`         | {count} 条记录 · {places} 个地点             |
| `momentsEyebrowNoPlaces` | {count} 条记录 · 都没有地点                  |
| `momentsFailed`          | 没能加载这段时间的记录。                     |
| `monthItem`              | {year} 年 {month} 月                         |
| `placeEyebrow`           | {count} 条记录 · {years}                     |
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
| `separator`              | ·                                            |
| `summary`                | 旅程回顾                                     |
| `tripMeta`               | {range} · {days} 天 · {moments} 条记录       |
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
| `drivingDayHoursLabel`   | 开了 {km} 公里 · {hours} 小时                |
| `drivingDayLabel`        | 开了 {km} 公里                               |
| `weatherClear`           | 晴                                           |
| `weatherDrizzle`         | 毛毛雨                                       |
| `weatherFog`             | 雾                                           |
| `weatherLabel`           | {kind} · {min}–{max}°C                       |
| `weatherOvercast`        | 阴                                           |
| `weatherPartlyCloudy`    | 多云                                         |
| `weatherRain`            | 雨                                           |
| `weatherShowers`         | 阵雨                                         |
| `weatherSnow`            | 雪                                           |
| `weatherSnowShowers`     | 阵雪                                         |
| `weatherThunderstorm`    | 雷雨                                         |

</details>

## Styling

`styles.css` styles everything the client puts on the page, mapbox-gl's own styles
included, and draws its icons as inline SVG (Lucide's), so you need neither Tailwind nor
an icon font. It reads your theme from these custom properties:

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

The package is a workspace member of hyoban.cc and is imported there from source;
`vp check` and `vp test` at the root cover it. `pnpm run build` here writes `dist/` (with
declaration files, source maps and the standalone stylesheet), which is what gets
published. See [CHANGELOG.md](./CHANGELOG.md) for versions.

## License

[MIT](./LICENSE). `dist` bundles [@hyoban/drawer](../drawer), a port of Base UI and shadcn/ui (both MIT), and mapbox-gl's stylesheet; their notices are in [THIRD-PARTY-NOTICES.md](./THIRD-PARTY-NOTICES.md).
