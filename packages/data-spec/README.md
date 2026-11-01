# @hyoban/data-spec

The Zod schemas for the records the Android app stores, the API serves, the site builds
from and `data/` keeps: entries, collections, tracks and places (`src/entry.ts`), the
first shape's Moments and trips, the use page's view of a device, and the Zeekr vehicle
records (`src/vehicle.ts`; see `data/zeekr/README.md`). A device and a subscription are
entries with their plugins' parts. `src/publish.ts` holds the rules for place
ids and media keys. `vp run data:schema` at the root writes them out as JSON Schema under
`data/schema`.

Every object is loose, so a field one side doesn't know yet travels through the other
untouched. Changes only add optional fields; any other change bumps `SCHEMA_VERSION`.
