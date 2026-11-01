-- Generated from packages/data-spec/src/tables.ts by `vp run data:tables`. Do not edit by hand.
-- The tables D1 keeps what leaves the phone in, as the app keeps its copy.

CREATE TABLE places (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX places_rev ON places (rev, key);

CREATE TABLE routes (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX routes_rev ON routes (rev, key);

CREATE TABLE collections (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL,
  uid GENERATED ALWAYS AS (json_extract(record, '$.uid')) VIRTUAL,
  start_day GENERATED ALWAYS AS (substr(json_extract(record, '$.span.start'), 1, 10)) VIRTUAL,
  end_day GENERATED ALWAYS AS (substr(json_extract(record, '$.span.end'), 1, 10)) VIRTUAL,
  visibility GENERATED ALWAYS AS (json_extract(record, '$.visibility')) VIRTUAL
);

CREATE INDEX collections_rev ON collections (rev, key);

CREATE INDEX collections_start_day ON collections (start_day, key);

CREATE TABLE entries (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL,
  at GENERATED ALWAYS AS (json_extract(record, '$.at')) VIRTUAL,
  day GENERATED ALWAYS AS (substr(json_extract(record, '$.at'), 1, 10)) VIRTUAL,
  kind GENERATED ALWAYS AS (CASE WHEN json_type(record, '$.parts.text') IS NOT NULL THEN 'moment' WHEN coalesce(json_extract(record, '$.parts'), '{}') = '{}' THEN 'mark' ELSE 'entry' END) VIRTUAL,
  place GENERATED ALWAYS AS (json_extract(record, '$.place')) VIRTUAL,
  visibility GENERATED ALWAYS AS (json_extract(record, '$.visibility')) VIRTUAL,
  published_at GENERATED ALWAYS AS (json_extract(record, '$.published.at')) VIRTUAL
);

CREATE INDEX entries_rev ON entries (rev, key);

CREATE INDEX entries_day ON entries (day, key);

CREATE TABLE tracks (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL,
  start_at GENERATED ALWAYS AS (json_extract(record, '$.start')) VIRTUAL,
  end_at GENERATED ALWAYS AS (json_extract(record, '$.end')) VIRTUAL,
  collection GENERATED ALWAYS AS (json_extract(record, '$.collection')) VIRTUAL
);

CREATE INDEX tracks_rev ON tracks (rev, key);

CREATE TABLE vehicle_months (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL,
  device_id GENERATED ALWAYS AS (json_extract(record, '$.deviceId')) VIRTUAL,
  month GENERATED ALWAYS AS (json_extract(record, '$.month')) VIRTUAL
);

CREATE INDEX vehicle_months_rev ON vehicle_months (rev, key);

CREATE TABLE vehicle_assisted (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL,
  device_id GENERATED ALWAYS AS (json_extract(record, '$.deviceId')) VIRTUAL,
  captured_at GENERATED ALWAYS AS (json_extract(record, '$.capturedAt')) VIRTUAL
);

CREATE INDEX vehicle_assisted_rev ON vehicle_assisted (rev, key);

CREATE TABLE vehicle_captures (
  key TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX vehicle_captures_rev ON vehicle_captures (rev, key);
