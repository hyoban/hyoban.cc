-- The Moments posted to the Telegram channel, each with what it was posted as and the
-- channel's messages it took, so a change to it edits them and a removal deletes them.
-- Only Moments published from now on are posted: `telegram_since` keeps when that began,
-- and `telegram_revision` how far the Moments have been looked through.

CREATE TABLE telegram_posts (
  uid TEXT PRIMARY KEY,
  post TEXT NOT NULL
);

INSERT INTO meta (key, value) VALUES
  ('telegram_since', strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
  ('telegram_revision', (SELECT value FROM meta WHERE key = 'revision'));
