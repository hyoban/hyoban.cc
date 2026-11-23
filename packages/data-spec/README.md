# @hyoban/data-spec

Zod schemas for the records the Android app stores, the API serves, the site builds from and
`data/` keeps.

| File                | Holds                                                                                                                       |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `src/entry.ts`      | entries, collections, tracks and modules; a device or subscription is an entry with its plugins' parts                      |
| `src/index.ts`      | places and routes                                                                                                           |
| `src/media.ts`      | media                                                                                                                       |
| `src/legacy.ts`     | the first layout's Moments, trips and devices                                                                               |
| `src/vehicle.ts`    | Zeekr vehicle records (see `data/zeekr/README.md`)                                                                          |
| `src/primitives.ts` | shared ids, times and amounts, and the units every record uses                                                              |
| `src/publish.ts`    | the media types the API accepts                                                                                             |
| `src/location.ts`   | the check that an image carries no location                                                                                 |
| `src/division.ts`   | looking up a place's province, city and district                                                                            |
| `src/tables.ts`     | the tables D1 and the app's copy use: key, revision, deletion time, record JSON, and columns SQLite derives from the record |

Generated from them, from the repository root:

- `vp run data:schema` writes JSON Schema to `data/schema`.
- `vp run data:tables` writes the tables as `apps/api/schema.sql`, the app's `CopyTables.kt`
  and the Apple apps' `CopyTables.swift`, and the modules of `src/entry.ts` as `Modules.kt`
  and `Modules.swift`.

Every object is loose, so a field one side doesn't know yet passes through the other
untouched. Changes only add optional fields; any other change bumps `SCHEMA_VERSION`.
