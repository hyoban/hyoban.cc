-- Nothing looked Moments up by place or by trip, so a Moment's write no longer keeps the
-- index of its places, and the trip index goes with it; the `trip` column stays.

DROP TABLE moment_places;
DROP INDEX moments_trip;
