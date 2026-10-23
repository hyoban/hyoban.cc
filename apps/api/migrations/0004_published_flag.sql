-- A published Moment is named by its uid alone now, so `published_id`, its old
-- `YYYY/MM/DD-NN-slug` id and unique, becomes a plain flag. SQLite can't drop a UNIQUE
-- column, so the table is made again with the rows copied across.

CREATE TABLE moments_next (
  uid TEXT PRIMARY KEY,
  occurred_at TEXT NOT NULL,
  -- The day it happened where it happened, YYYY-MM-DD.
  day TEXT NOT NULL,
  -- 1 once it is published.
  published INTEGER NOT NULL DEFAULT 0,
  trip TEXT,
  hidden INTEGER NOT NULL DEFAULT 0,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

INSERT INTO moments_next (uid, occurred_at, day, published, trip, hidden, rev, deleted_at, record)
SELECT uid, occurred_at, day, published_id IS NOT NULL, trip, hidden, rev, deleted_at, record
FROM moments;

DROP TABLE moments;
ALTER TABLE moments_next RENAME TO moments;

CREATE INDEX moments_rev ON moments (rev, uid);
CREATE INDEX moments_day ON moments (day);

-- The records' media are named by uids in this version of the spec.
UPDATE meta SET value = json_set(value, '$.schemaVersion', 2) WHERE key = 'manifest';
