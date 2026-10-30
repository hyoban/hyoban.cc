---
name: create-moment
description: Create a hyoban.cc Moment from exact user text and media. Use when adding or recording a Moment through conversation.
---

# Create Moment

Create one Moment as an entry under `data/entries` (the shape is in
`packages/data-spec/src/entry.ts`: its words in `parts.text.text`, its photos in
`parts.media.items`, its place in `place`), then push it into the API, which holds the records;
`data/` is their backup. Never deploy the site or publish the Moment to a social
network. Uploading its media to the configured R2 bucket is part of preparing
the local Moment.

1. Run `vp run data:export` so `data/` has what the API has; it needs
   `MOMENTS_API_TOKEN` in `.env`, and stops if `data/` has uncommitted changes
   (ask the user about those). Then inspect `packages/data-spec`,
   `data/places.json`, and a recent entry under `data/entries`.
2. Preserve the user's text exactly, including errors, punctuation, paragraphs, and line breaks.
3. Use one `occurredAt` value for when the Moment happened. Use an ISO timestamp
   with an explicit offset when the time is known, otherwise use `YYYY-MM-DD`.
   Never add publication or source fields. If the text names a specific place,
   use that place instead of its city; reuse or add it in `data/places.json`
   and verify new coordinates (see Coordinates below). A Moment is at one place
   at most: when the text covers several, write one Moment for each, splitting
   its words and media between them in visiting order. A Moment is named by its
   uid alone, which the command below makes; its page is `/m/<uid>/`.
4. Process and upload images or videos in order from the repository root:

```bash
node .agents/skills/create-moment/scripts/prepare-media.mjs --repo <repo> --occurred-at <occurredAt> [--place <id>] [--uid <uid>] -- <media...>
```

The command optimizes images and responsive variants, transcodes videos to
web-compatible MP4 with a poster, names each file by its media item's uid,
uploads or safely reuses and verifies them under `moments/<uid>/` in R2, and
then writes the Moment's entry under `data/entries` with every media item
published, its place set, and empty words and `alt`. It prints the record's
file and uid. To resume an interrupted run, rerun it with `--uid <uid>` from
the record it wrote.

5. Inspect each source media item and replace every empty `alt` in
   `parts.media.items` with concise Chinese alt text. Visible new Moments must never use an empty alt.
6. Set `parts.text.text` in the entry with `apply_patch`. Leave the other fields as the
   command wrote them; never edit `uid`, `published` or a media item's
   `published` by hand.
7. Compare the saved text with the user's text, then run `vp test`, `vp check`, and `vp run build`.
8. Push the record, with the places it names, into the API, and any trip you
   changed:

```bash
vp run data:push data/entries/<YYYY>/<MM>/<uid>.json [data/collections/<slug>.json]...
```

Add `--place <id>` for a place you moved that the entry doesn't name. A trip is a
collection: its waypoints are entries with a `place` and no parts, its legs not driven in
`route.straightLegs`; push a collection with the entries it changed. Until
it is pushed the Moment isn't on the site, and the next backup would remove
it from `data/`.

## Coordinates

The calendar map draws WGS-84 points on the Mapbox basemap, so a place should sit
where Mapbox labels it. AMap and other Chinese maps use GCJ-02, which is a few
hundred metres off; never store their coordinates as they are. Even after
conversion, AMap and Mapbox often disagree by around 100 m on the same shop.

Start from the user's AMap link (resolve `surl.amap.com` with `curl -sI`; its
`p` parameter holds the POI id, GCJ-02 latitude and longitude, name, and address)
or another lookup, then:

```bash
node .agents/skills/create-moment/scripts/find-place.mjs <latitude> <longitude> <name> --gcj
```

Drop `--gcj` for WGS-84 input. If it lists a Mapbox POI that is the same place,
use that POI's coordinates; otherwise use the converted point it prints. Cities
and regions keep an approximate centre.

- Only Mapbox tilequery (`poi_label`) covers China. The Mapbox Search Box and
  geocoding APIs, Nominatim, and Baidu's web endpoints return nothing useful for
  Chinese shops. The script already queries around the point, because tilequery
  stops at the 50 nearest POIs.
- A matching name is not always the same place. Reject a nearby visitor centre,
  campsite, memorial, school, or a different branch, and keep the point the
  Moment is about (a summit, a beach, a lake shore) for large scenic areas.
- Without a link from the user, search AMap in the built-in browser: open
  `https://www.amap.com`, have the user sign in, and fetch
  `/service/poiInfo?query_type=TQUERY&pagesize=5&pagenum=1&city=<adcode>&keywords=<name>`
  (or `geoobj=<minLng>|<minLat>|<maxLng>|<maxLat>` instead of `city`). It returns
  GCJ-02. The REST API needs a key, and searching without signing in hits a login
  wall. Space requests several seconds apart; a burst brings up a slider CAPTCHA,
  which the user must solve. Check the returned branch name matches.
- When several candidates fit (which noodle shop, which station), ask the user
  instead of guessing.
- Some older entries in `data/places.json` were stored in GCJ-02. When a
  point looks a few hundred metres off, compare it with AMap's raw and converted
  coordinates: if it sits near the raw one, convert it.
- Trip routes are kept by coordinates in the API, which fetches the missing ones
  after each write and after `vp run data:push`. After moving a place or locating a
  Moment inside a trip, run `vp run trips:weather`. If the new place now ends a
  train, plane, or ferry leg, update that trip's `route.straightLegs` in
  `data/collections/<slug>.json`, or the leg turns into a drive; a new train endpoint
  also needs its `station` in `data/places.json`.
- Changing an existing Moment's entry, even only its `place`, makes CI
  require non-empty alt text on its visible media, so describe those images too.

Only perform Git operations when requested. Commit directly on `main`; do not create a branch.
