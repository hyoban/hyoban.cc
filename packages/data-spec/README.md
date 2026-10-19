# @hyoban/data-spec

The data spec: the Zod schemas for the Moments, places, trips and devices that the
recording app stores, the site builds from and `data/` keeps, with the helpers that
name a Moment's file. `vp run data:schema` at the root writes them out as JSON Schema
under `data/schema` for the app.

Every object is loose, so a field one side doesn't know yet travels through the other
untouched. Changes only add optional fields; a change that doesn't bumps
`SCHEMA_VERSION`.
