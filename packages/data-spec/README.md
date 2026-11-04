# @hyoban/data-spec

The Zod schemas for the records the Android app stores, the API serves, the site builds
from and `data/` keeps: entries, collections and tracks (`src/entry.ts`), places and
routes (`src/index.ts`), media (`src/media.ts`), the first shape's Moments, trips and
devices (`src/legacy.ts`), and the Zeekr vehicle records (`src/vehicle.ts`; see
`data/zeekr/README.md`). `src/primitives.ts` holds the ids, times and amounts they share,
and the units every record keeps to. A device and a subscription are
entries with their plugins' parts. `src/publish.ts` holds the rules for place
ids and media keys. `vp run data:schema` at the root writes them out as JSON Schema under
`data/schema`. `src/tables.ts` is the tables D1 and the app's copy keep the records in, a
key, a revision, when deleted and the record's JSON, with the other columns SQLite works
out from the record; `vp run data:tables` writes them out as `apps/api/schema.sql` and the
apps' `CopyTables.kt` and `CopyTables.swift`.

Every object is loose, so a field one side doesn't know yet travels through the other
untouched. Changes only add optional fields; any other change bumps `SCHEMA_VERSION`.
