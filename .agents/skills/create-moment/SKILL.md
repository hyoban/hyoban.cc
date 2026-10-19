---
name: create-moment
description: Create a hyoban.cc Moment from exact user text and media. Use when adding or recording a Moment through conversation.
---

# Create Moment

Create one Moment as a record under `data/moments` (the shape is in
`packages/data-spec`), then push it into the API, which holds the records;
`data/` is their backup. Never deploy the site or publish the Moment to a social
network. Uploading its media to the configured R2 bucket is part of preparing
the local Moment.

1. Run `pnpm data:export` so `data/` has what the API has; it needs
   `MOMENTS_API_TOKEN` in `.env`, and stops if `data/` has uncommitted changes
   (ask the user about those). Then inspect `packages/data-spec`,
   `data/places.json`, and a recent record under `data/moments`.
2. Preserve the user's text exactly, including errors, punctuation, paragraphs, and line breaks.
3. Use one `occurredAt` value for when the Moment happened. Use an ISO timestamp
   with an explicit offset when the time is known, otherwise use `YYYY-MM-DD`.
   Never add publication or source fields. If the text names a specific place,
   use that place instead of its city; reuse or add it in `data/places.json`
   and verify new coordinates (see Coordinates below). When the Moment covers
   several places, list their ids in visiting order. Use
   `YYYY/MM/DD-NN-short-slug` for the published id, where `NN` is the next
   available two-digit order for that occurrence date (check the records of
   that day under `data/moments/YYYY/MM`).
4. Process and upload images or videos in order from the repository root:

```bash
node .agents/skills/create-moment/scripts/prepare-media.mjs --repo <repo> --id <YYYY/MM/DD-NN-short-slug> --occurred-at <occurredAt> [--place <id>]... -- <media...>
```

The command optimizes images and responsive variants, transcodes videos to
web-compatible MP4 with a poster, gives files semantic names from the slug,
uploads or safely reuses and verifies them in the matching R2 directory, and
then writes the Moment's record under `data/moments` with every media item
published, its places set, and empty `text` and `alt`. It prints the record's
file. It is safe to rerun after an interrupted upload.

5. Inspect each source media item and replace every empty `alt` in the record
   with concise English alt text. Visible new Moments must never use an empty alt.
6. Set `text` in the record with `apply_patch`. Leave the other fields as the
   command wrote them; never edit `uid`, `published` or a media item's
   `published` by hand.
7. Compare the saved text with the user's text, then run `pnpm test`, `pnpm check`, and `pnpm build`.
8. Push the record, with the places it names, into the API, and any trip you
   changed:

```bash
pnpm data:push data/moments/<YYYY>/<MM>/<uid>.json [data/trips/<id>.json]...
```

Add `--place <id>` for a place you moved that the record doesn't name. Until
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
  after each write and after `pnpm data:push`. After moving a place or locating a
  Moment inside a trip, run `pnpm trips:weather`. If the new place now ends a
  train, plane, or ferry leg, update that trip's `straightLegs` in
  `data/trips/<id>.json`, or the leg turns into a drive; a new train endpoint
  also needs its `station` in `data/places.json`.
- Changing an existing Moment's record, even only its `places`, makes CI
  require non-empty alt text on its visible media, so describe those images too.

Only perform Git operations when requested. Commit directly on `main`; do not create a branch.
