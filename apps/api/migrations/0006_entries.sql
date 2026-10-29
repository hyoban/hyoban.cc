-- Records become entries and trips collections (@hyoban/data-spec/entry). Each row keeps both
-- shapes while the installed app still reads the first: `record` as it always was for a Moment
-- or a trip, `entry` and `collection` in the second shape, and `shown`, what of it the public
-- sees, NULL when nothing is. `kind` sets apart the entries the first shape has no Moment for:
-- marks (a place passed) and the rest (a bill alone, a charge), which the first shape's reads
-- leave out. Rows written before the data moved over have no second shape yet; the reads
-- work it out from `record` until an import fills it.

ALTER TABLE moments ADD COLUMN kind TEXT NOT NULL DEFAULT 'moment';
ALTER TABLE moments ADD COLUMN entry TEXT;
ALTER TABLE moments ADD COLUMN shown TEXT;

ALTER TABLE trips ADD COLUMN uid TEXT;
ALTER TABLE trips ADD COLUMN collection TEXT;
ALTER TABLE trips ADD COLUMN shown TEXT;

-- The spans of footprints made public, each with its simplified line.
CREATE TABLE tracks (
  uid TEXT PRIMARY KEY,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL,
  collection TEXT,
  rev INTEGER NOT NULL,
  deleted_at TEXT,
  record TEXT NOT NULL
);

CREATE INDEX tracks_rev ON tracks (rev, uid);
