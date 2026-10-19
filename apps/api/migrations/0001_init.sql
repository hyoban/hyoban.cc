-- Each table keeps the record as written in `record`, so a field the spec doesn't
-- know yet survives, plus the columns the API looks things up by. Nothing is ever
-- removed: `deleted_at` marks a record gone, and `rev`, the database's revision when
-- the row last changed, lets a client ask for everything since the last time.

CREATE TABLE meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

INSERT INTO meta (key, value) VALUES ('revision', '0');

CREATE TABLE moments (
  uid TEXT PRIMARY KEY,
  occurred_at TEXT NOT NULL,
  -- The day it happened where it happened, YYYY-MM-DD.
  day TEXT NOT NULL,
  published_id TEXT UNIQUE,
  trip TEXT,
  hidden INTEGER NOT NULL DEFAULT 0,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX moments_rev ON moments (rev, uid);
CREATE INDEX moments_day ON moments (day);
CREATE INDEX moments_trip ON moments (trip);

-- A Moment's places in visiting order, for looking Moments up by place.
CREATE TABLE moment_places (
  moment_uid TEXT NOT NULL REFERENCES moments (uid) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  place_id TEXT NOT NULL,
  PRIMARY KEY (moment_uid, position)
);

CREATE INDEX moment_places_place ON moment_places (place_id);

CREATE TABLE places (
  id TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE TABLE trips (
  id TEXT PRIMARY KEY,
  start_date TEXT NOT NULL,
  end_date TEXT NOT NULL,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);
