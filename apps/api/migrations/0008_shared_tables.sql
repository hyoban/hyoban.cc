-- Every table becomes one of @hyoban/data-spec/tables (apps/api/schema.sql), the same as the
-- app's copy keeps: a key, the revision it last changed at, when it was deleted and the record
-- in JSON, and columns SQLite works out from the record. Moments and trips become entries and
-- collections in their second shape alone; the first shape, kept beside them since
-- 0006_entries.sql, goes. Every row keeps its key, revision and deletion, so a client that has
-- synced carries on from where it was.

-- The tables as they were, out of the way, without the indexes whose names the new ones take.
ALTER TABLE places RENAME TO places_v1;
ALTER TABLE routes RENAME TO routes_v1;
ALTER TABLE tracks RENAME TO tracks_v1;
ALTER TABLE vehicle_months RENAME TO vehicle_months_v1;
ALTER TABLE vehicle_assisted RENAME TO vehicle_assisted_v1;
ALTER TABLE vehicle_captures RENAME TO vehicle_captures_v1;
DROP INDEX tracks_rev;
DROP INDEX vehicle_months_rev;
DROP INDEX vehicle_assisted_rev;
DROP INDEX vehicle_captures_rev;

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

INSERT INTO places (key, rev, deleted_at, record) SELECT id, rev, deleted_at, record FROM places_v1;
INSERT INTO routes (key, rev, deleted_at, record) SELECT key, rev, deleted_at, record FROM routes_v1;
INSERT INTO tracks (key, rev, deleted_at, record) SELECT uid, rev, deleted_at, record FROM tracks_v1;
INSERT INTO vehicle_months (key, rev, deleted_at, record)
  SELECT key, rev, deleted_at, record FROM vehicle_months_v1;
INSERT INTO vehicle_assisted (key, rev, deleted_at, record)
  SELECT key, rev, deleted_at, record FROM vehicle_assisted_v1;
INSERT INTO vehicle_captures (key, rev, deleted_at, record)
  SELECT id, rev, deleted_at, record FROM vehicle_captures_v1;

-- Every trip has its collection. A row with no second shape fails the migration, record being
-- NOT NULL, rather than going without, except a Moment deleted before 0006_entries.sql: it
-- stays deleted as an entry of its uid, time and stamps alone.
INSERT INTO collections (key, rev, deleted_at, record)
  SELECT id, rev, deleted_at, collection FROM trips;
INSERT INTO entries (key, rev, deleted_at, record)
  SELECT uid, rev, deleted_at, coalesce(entry, CASE WHEN deleted_at IS NOT NULL THEN json_object(
    'uid', uid,
    'at', occurred_at,
    'parts', json_object(),
    'createdAt', coalesce(json_extract(record, '$.createdAt'), occurred_at),
    'updatedAt', deleted_at
  ) END)
  FROM moments;

DROP TABLE places_v1;
DROP TABLE routes_v1;
DROP TABLE tracks_v1;
DROP TABLE vehicle_months_v1;
DROP TABLE vehicle_assisted_v1;
DROP TABLE vehicle_captures_v1;
DROP TABLE trips;
DROP TABLE moments;
