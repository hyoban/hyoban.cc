# @hyoban/trip-map

A map of the places a site's Moments were written at, and a player that replays its
trips along their routes, on [Mapbox GL JS](https://docs.mapbox.com/mapbox-gl-js/).
Extracted from [hyoban.cc](https://hyoban.cc/map/); the site's `/map/` and `/trip/<id>/`
pages are the reference use.

## What's in it

| Entry                         | Runs in  | Holds                                                                                                                                                                                            |
| ----------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `@hyoban/trip-map`            | anywhere | The data model: `defineLocations`, `defineTrips`, `buildTrip` and the keys for the cached routes and weather; the DOM-free helpers that shape a built trip for the player; the `TripData` types. |
| `@hyoban/trip-map/client`     | browser  | `initializeCalendarMap` / `destroyCalendarMap` for the map page, `createTripPlayer`, `createTripOverview`, and `captureMap` for the screenshot. Imports mapbox-gl.                               |
| `@hyoban/trip-map/dom`        | browser  | Small DOM builders the player uses, such as `createCountUp` (a number rolling up with number-flow).                                                                                              |
| `@hyoban/trip-map/preview`    | Node     | `renderTripPreview`: a trip's route beside a cover photo as a PNG, with [sharp](https://sharp.pixelplumbing.com/) (an optional peer dependency).                                                 |
| `@hyoban/trip-map/styles.css` | browser  | The map's and the player's styles, mapbox-gl's included.                                                                                                                                         |

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
   hyoban.cc). Days' weather is cached under `getWeatherKey(dateKey, place)` and turned
   into a label and icon with `describeWeather`.

3. **Shape the player's data.** The player plays a `TripData` (one trip, with each stop's
   photos and words) and the overview draws `TripSummary`s (every trip's route). The site
   builds both from its built trips and its media; `getTripData` and `getTripSummary` in
   hyoban.cc's `src/moments/trip-data.ts` show how. The route helpers it needs
   (`getRouteLine`, `simplifyLine`) and the intro card's text (`getTripMeta`,
   `getIntroCovers`) are exported from the package root so the server can render them.

4. **Render the map page's markup** and inline the data:

   - `[data-calendar-map]` with `data-mapbox-token` and `data-map-title` on it, holding
     `[data-calendar-map-canvas]`, the drawer (`[data-calendar-map-drawer]` and its
     `-title`, `-eyebrow`, `-content`, `-close`), the loading and fallback messages, the
     lightbox dialog, the trip card (`[data-calendar-map-trip]` and its controls) and the
     presentation stage (`[data-calendar-map-trip-stage]`).
   - `<script type="application/json" data-calendar-map-locations>` with each place's
     `{ id, name, latitude, longitude, count, latestYear, years, color }`,
     `data-calendar-map-trips` with the `TripSummary`s, `data-calendar-map-stats` for the
     screenshot's header, and `data-calendar-map-trip-data` with the opening trip's
     `TripData` on a trip page.

   `src/components/CalendarMap.astro` in hyoban.cc is the complete reference; the client
   throws naming the first element it can't find.

5. **Start the client** once the page has painted:

   ```ts
   import { destroyCalendarMap, initializeCalendarMap } from '@hyoban/trip-map/client'
   import '@hyoban/trip-map/styles.css'

   initializeCalendarMap({
     screenshotTitle: 'Hyoban 的足迹',
     siteLine: 'hyoban.cc/trip/',
     // Optional: the drawer's lightbox, and the URLs when they differ from the defaults
     // /map/, /trip/<id>/, /trip/<id>/data.json and /map/place/<id>/.
   })
   // Before the page is replaced (a router's before-swap hook):
   destroyCalendarMap()
   ```

   Serve each place's drawer content as an HTML fragment and each trip's `TripData` as
   JSON at the URLs above (or pass `getPlaceUrl` / `getTripDataUrl`).

## Styling

Everything the client puts on the page is styled by `styles.css`, with its icons as
inline SVG (Lucide's), so a site needs neither Tailwind nor an icon font. The stylesheet
reads the page's theme from these custom properties, which the site defines on `:root`
or on the map page:

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

The page's own markup (toolbar, drawer, trip card, stage) is the site's to lay out; in
hyoban.cc it uses Tailwind, but any CSS will do. `renderIcon(name)` from the package root
gives the same icons as markup for buttons the player later toggles, such as play/pause.

## Development

Inside the hyoban.cc workspace the package is imported from source, so there is nothing to
build while developing; `vp check` and `vp test` at the root cover it. `vp pack` (from the
root or this directory) builds `dist/` with declaration files for publishing, and
`publishConfig.exports` points the published package at it.
