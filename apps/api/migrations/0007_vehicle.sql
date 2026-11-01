-- The car's statistics as the Zeekr import normalised them, kept here rather than only in the
-- repository. They are not records but figures about the car, like the routes: a month by
-- `<device>/<YYYY-MM>`, a cumulative assisted-driving observation by `<device>/<captured at>`,
-- and the source captures they point into by their SHA-256. Only the import writes them, and
-- all of it is public, as the owner chose. Devices and subscriptions are entries.

CREATE TABLE vehicle_months (
  key TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  month TEXT NOT NULL,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX vehicle_months_rev ON vehicle_months (rev, key);

CREATE TABLE vehicle_assisted (
  key TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  captured_at TEXT NOT NULL,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX vehicle_assisted_rev ON vehicle_assisted (rev, key);

CREATE TABLE vehicle_captures (
  id TEXT PRIMARY KEY,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX vehicle_captures_rev ON vehicle_captures (rev, id);
