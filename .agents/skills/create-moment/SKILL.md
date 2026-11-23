---
name: create-moment
description: Create a hyoban.cc Moment from exact user text and media. Use when adding or recording a Moment through conversation.
---

# Create Moment

A Moment is one entry under `data/entries` (shape: `packages/data-spec/src/entry.ts`;
words in `parts.text.text`, photos in `parts.media.items`, place in `place`). The API
holds the records; `data/` is a git-ignored working copy. Upload the Moment's media to
the configured R2 bucket, then push the entry. Never deploy the site or post the Moment
to a social network.

1. Run `vp run data:export` (needs `MOMENTS_API_TOKEN` in `.env`). It stops if `data/`
   has records written since the last export or push; ask the user about those. Then
   read `packages/data-spec`, `data/places.json` and a recent entry.
2. Keep the user's text exactly: errors, punctuation, paragraphs and line breaks.
3. Pick one `--occurred-at` (the entry's `at`): an ISO timestamp with an explicit offset
   when the time is known, else `YYYY-MM-DD`. Never add publication or source fields.
   - If the text names a specific place, use it, not its city. Reuse or add it in
     `data/places.json`, verifying new coordinates (see Coordinates).
   - A Moment has at most one place. If the text covers several, write one Moment per
     place, splitting words and media between them in visiting order.
   - A Moment is named only by its uid, which the command below makes; its page is
     `/m/<uid>/`.
4. From the repository root, process and upload the media in order:

   ```bash
   node .agents/skills/create-moment/scripts/prepare-media.mjs --repo <repo> --occurred-at <occurredAt> [--place <id>] [--uid <uid>] -- <media...>
   ```

   It optimizes images (with responsive variants), transcodes videos to web MP4 with a
   poster, names each file by its media item's uid, and uploads (or safely reuses) and
   verifies them under `moments/<uid>/` in R2. It then writes the entry with every
   media item published, its place set, and empty words and `alt`, and prints the file
   and uid. To resume an interrupted run, rerun with `--uid <uid>` from that record.

5. Look at each source item and fill every empty `alt` in `parts.media.items` with
   concise Chinese alt text. A visible new Moment never has an empty alt.
6. Set `parts.text.text` with `apply_patch`. Leave the other fields as written; never
   hand-edit `uid`, `published` or a media item's `published`.
7. Compare the saved text with the user's text, then run `vp run data:check`.
8. Push the entry, with the places it names, and any trip you changed:

   ```bash
   vp run data:push data/entries/<YYYY>/<MM>/<uid>.json [data/collections/<slug>.json]...
   ```

   - Add `--place <id>` for a moved place the entry doesn't name.
   - A trip is a collection: its waypoints are entries with a `place` and no parts, its
     legs not driven are in `route.straightLegs`. Push a collection with the entries it
     changed.
   - Until pushed, the Moment isn't on the site and `data:export` refuses to overwrite it.

## Coordinates

The calendar map draws WGS-84 points on Mapbox, so a place should sit where Mapbox
labels it. AMap and other Chinese maps use GCJ-02, a few hundred metres off: never store
their coordinates raw. Even converted, AMap and Mapbox often differ by about 100 m on
the same shop.

Start from the user's AMap link (resolve `surl.amap.com` with `curl -sI`; its `p`
parameter holds the POI id, GCJ-02 latitude and longitude, name and address) or another
lookup, then run:

```bash
node .agents/skills/create-moment/scripts/find-place.mjs <latitude> <longitude> <name> --gcj
```

Drop `--gcj` for WGS-84 input. If it lists a Mapbox POI that is the same place, use that
POI's coordinates; otherwise use the converted point it prints. Cities and regions keep
an approximate centre.

- Only Mapbox tilequery (`poi_label`) covers China. Mapbox Search Box and geocoding,
  Nominatim and Baidu's web endpoints return nothing useful for Chinese shops. The
  script already queries around the point, since tilequery stops at the 50 nearest POIs.
- A matching name isn't always the same place. Reject a nearby visitor centre,
  campsite, memorial, school or other branch. For large scenic areas keep the point the
  Moment is about (a summit, a beach, a lake shore).
- Without a link, search AMap in the built-in browser: open `https://www.amap.com`, have
  the user sign in, and fetch
  `/service/poiInfo?query_type=TQUERY&pagesize=5&pagenum=1&city=<adcode>&keywords=<name>`
  (or `geoobj=<minLng>|<minLat>|<maxLng>|<maxLat>` instead of `city`). It returns
  GCJ-02. The REST API needs a key; searching signed out hits a login wall. Space
  requests several seconds apart, since a burst brings up a slider CAPTCHA the user must
  solve. Check the returned branch name matches.
- When several candidates fit (which noodle shop, which station), ask the user.
- Some older places in `data/places.json` are GCJ-02. If a point looks a few hundred
  metres off, compare it with AMap's raw and converted coordinates; if it sits near the
  raw one, convert it.
- The API keeps trip routes by coordinates and fetches missing ones after each write
  and `vp run data:push`. After moving a place or locating a Moment inside a trip, run
  `vp run trips:weather`. If the new place now ends a train, plane or ferry leg, update
  that trip's `route.straightLegs` in `data/collections/<slug>.json`, or the leg becomes
  a drive; a new train endpoint also needs its `station` in `data/places.json`.
- After changing an existing Moment's entry, even only its `place`, the Moment audit
  warns about empty alt on its visible media, so describe those too.

Only perform Git operations when requested.
