# @hyoban/data-spec

The data spec: the Zod schemas for the Moments, places, trips and devices that the
recording app stores, the site builds from and `data/` keeps, with the helpers that
name a Moment's file. `vp run data:schema` at the root writes them out as JSON Schema
under `data/schema` for the app.

`src/vehicle.ts` also defines the repository-only vehicle captures, month records
and cumulative assisted-driving observations. Vehicle records refer to an existing
device id. Distances are integer metres, energy integer Wh and durations integer
seconds; converted screen durations retain the source's rounding. Source references
name a statistical source hash and a JSON Pointer; public sources are explicitly
redacted while unmodified captures remain private. See `data/zeekr/README.md` for
conversion, provenance and validation. These records are not part of the API export.

Every object is loose, so a field one side doesn't know yet travels through the other
untouched. Changes only add optional fields; a change that doesn't bumps
`SCHEMA_VERSION`.
