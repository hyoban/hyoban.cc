# @hyoban/trip-map

A map of the places a site's Moments were written at, and a player that replays its
trips along their routes, on [Mapbox GL JS](https://docs.mapbox.com/mapbox-gl-js/).
Extracted from [hyoban.cc](https://hyoban.cc/map/); the site's `/map/` and `/trip/<id>/`
pages are the reference use.

## What's in it

| Entry                         | Runs in  | Holds                                                                                                                                                                                       |
| ----------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `@hyoban/trip-map`            | anywhere | The model (`defineLocations`, `defineTrips`, `buildTrip`), its shaping for the player (`shapeTripData`, `getTripSummary`), the markup the player drives (`renderTripPanel`…) and the words. |
| `@hyoban/trip-map/client`     | browser  | `initializeTripMap` / `destroyTripMap` for the map page, and the pieces it is made of: `createTripPlayer`, `createTripOverview`, `captureMap`. Imports mapbox-gl.                           |
| `@hyoban/trip-map/dom`        | browser  | `createCountUp` (a number rolling up with number-flow) and `createIcon`, for a site's own pages.                                                                                            |
| `@hyoban/trip-map/preview`    | Node     | `renderTripPreview`: a trip's route beside a cover photo as a PNG, with [sharp](https://sharp.pixelplumbing.com/) (an optional peer dependency).                                            |
| `@hyoban/trip-map/styles.css` | browser  | Everything the map and the player put on the page, mapbox-gl's styles included.                                                                                                             |

## How a site uses it

1. **Define places and trips.** Places are `{ latitude, longitude, name }` by id; a trip is
   a date range, with optional waypoints (places passed without a Moment), `straightLegs`
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

2. **Build each trip** from its Moments (anything with `dateKey`, `locations` and
   `occurredAt`, oldest first), resolving location ids and passing the cached road routes:

   ```ts
   import { buildTrip, getLegKey, getTrainLegKey, getWeatherKey } from '@hyoban/trip-map'

   const trip = buildTrip(definition, moments, (id) => ({ id, ...locations[id] }), routes)
   ```

   `routes` maps `getLegKey(from, to)` (or `getTrainLegKey` for a train leg) to
   `{ coordinates, distance, duration }`; the site fetches them from the Mapbox Directions
   API and OpenRailRouting once and caches them (see `scripts/generate-trip-routes.mjs` in
   hyoban.cc). Days' weather is cached under `getWeatherKey(dateKey, place)`.

3. **Shape the player's data.** `shapeTripData` turns a built trip into the `TripData` the
   player plays; the site adds what only it knows about each stop (its photos, words, link
   and anchor), the cover photos, the music and the cached weather. `getTripSummary` gives
   the overview every trip's route.

   ```ts
   const data = shapeTripData(trip, {
     covers,
     describeStop: (stop) => ({ anchor, href, images, text, time, video }),
     music: '/music/apple-cider.mp3',
     weather: (dateKey, place) => cache[getWeatherKey(dateKey, place)],
   })
   ```

   Serve it as JSON at `/trip/<id>/data.json` (or wherever `getTripDataUrl` says), and
   inline it in a trip's own page as `<script type="application/json" data-trip-map-trip>`.

4. **Render the map page.** The page's layout is the site's; the player's parts are the
   package's, rendered into containers the site provides. On the root `[data-trip-map]`
   (with `data-trip-map-token` unless the token is passed as an option):

   - `[data-trip-map-canvas]` for the map.
   - `[data-trip-map-loading]`, and `[data-trip-map-fallback]` with `-message`, `-retry`
     and a `[data-trip-map-place="<id>"]` button per place, for when the map can't load.
   - The place drawer: `[data-trip-map-drawer]` with `-title`, `-eyebrow`, `-content` and
     `-close`; optionally `[data-trip-map-lightbox]` (a `<dialog>`) with `-close`, `-previous`
     and `-next` buttons, driven through `createLightboxMedia`.
   - Three containers the package fills: `[data-trip-map-panel]` (the side card),
     `[data-trip-map-stage]` (the presentation layout) and `[data-trip-map-shortcuts]` (a
     `<dialog>`). Render them on the server with `renderTripPanel`, `renderTripStage` and
     `renderShortcuts` so a trip page opens on its intro card before any script runs, or
     leave them empty and the client fills them.
   - Optional: `[data-trip-map-toolbar]`, `[data-trip-map-legend]`, `[data-trip-map-screenshot]`
     and `[data-trip-map-trip-link="<id>"]` links.
   - `<script type="application/json">` blocks: `data-trip-map-places` (each place's
     `{ id, name, latitude, longitude, count, latestYear, years, color }`, with colours from
     `createYearPalette`), `data-trip-map-trips` (the `TripSummary`s) and
     `data-trip-map-stats` for the screenshot's header.

   `src/components/CalendarMap.astro` in hyoban.cc is the complete reference; the client
   throws naming every element it can't find.

5. **Start the client** once the page has painted:

   ```ts
   import { destroyTripMap, initializeTripMap } from '@hyoban/trip-map/client'
   import '@hyoban/trip-map/styles.css'

   initializeTripMap({
     brand: { image: '/avatar.webp', name: 'Hyoban' },
     getDocumentTitle: (trip) => `${trip.title} · 旅程 | Hyoban`,
     messages: { videoTitle: '哔哩哔哩视频' },
     music: [{ src: '/music/apple-cider.mp3', title: 'Apple Cider' }],
     screenshotTitle: 'Hyoban 的足迹',
     siteLine: 'hyoban.cc/trip/',
     // Also: createLightboxMedia, mapboxToken, and the URLs when they differ from the
     // defaults /map/, /trip/<id>/, /trip/<id>/data.json and /map/place/<id>/.
   })
   // Before the page is replaced (a router's before-swap hook):
   destroyTripMap()
   ```

   One map and one player per page: the player's shortcuts listen on the window.

## Words and dates

Every word the map and the player show is in `messages.ts`, as a template with `{slots}`;
pass `messages` to replace any of them, and `locale` for how dates and numbers are
formatted. Dates travel as `YYYY-MM-DD` keys and are formatted where they are shown.

## Styling

Everything the client puts on the page is styled by `styles.css`, with its icons as
inline SVG (Lucide's), so a site needs neither Tailwind nor an icon font. The stylesheet
reads the page's theme from these custom properties:

```css
:root {
  --color-bg: #fdfdfc;
  --color-border: #e9e9e7;
  --color-inline: #f9f9f8;
  --color-tx: #21201c;
  --color-tx-2: #63635e;
  --color-tx-3: #73736c;
  /* Optional: the numbers' typeface. */
  --font-mono: ui-monospace, monospace;
}
```

The page's own boxes (toolbar, drawer, the panel's and stage's outer frames) are the
site's to lay out; in hyoban.cc they use Tailwind, but any CSS will do.

## Layout of the source

- `trips.ts`, `locations.ts`, `shape.ts`, `weather.ts`, `format.ts`, `messages.ts`: the
  model and its shaping, DOM-free.
- `markup.ts`, `elements.ts`, `icons.ts`, `theme.ts`: what the client renders and finds.
- `map/`: the map page: `trip-map.ts` wires `place-markers.ts`, `place-drawer.ts`,
  `selection-url.ts` and `screenshot.ts` together.
- `player/`: `player.ts` keeps the time and wires `route-plan.ts` (every index into the
  route), `route-drawer.ts` (drawing it), `side-card.ts`, `stage.ts` (presenting and
  recording), `camera.ts`, `map-labels.ts` and the DOM-free `slides.ts` and `timeline.ts`.

## Development

Inside the hyoban.cc workspace the package is imported from source, so there is nothing to
build while developing; `vp check` and `vp test` at the root cover it. `vp pack` (from the
root or this directory) builds `dist/` with declaration files for publishing, and
`publishConfig.exports` points the published package at it.
