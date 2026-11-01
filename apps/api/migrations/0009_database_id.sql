-- Which database this is, a number at random that `GET /v2/rows` hands out with the rows. A copy
-- that kept another is of a database since put back or made again, which a revision gone back
-- doesn't always show (writes after a restore can take it past the copy's), so it is read
-- again from the start. A restore with Time Travel puts this back too: give it a new one then
-- (docs/deploy.md).

INSERT INTO meta (key, value) VALUES ('database', CAST(abs(random() % 9007199254740991) AS TEXT));
