# Changelog

## 0.1.0

The first release, extracted from [hyoban.cc](https://hyoban.cc/map/).

- A map of places with posts, clustered where they crowd, each opening a drawer the
  site serves as an HTML fragment.
- A player that replays a trip along its roads, rail lines, ferries and flights, with
  a card for each post, a presentation layout, and recording to a video.
- The DOM-free model (`defineLocations`, `defineTrips`, `buildTrip`), its shaping for
  the player (`shapeTripData`, `getTripSummary`, `getPlaceMarkers`, `getMapStats`),
  and the markup the player drives, for pages rendered on the server.
- Every word replaceable through `messages`; dates and numbers in the messages' locale.
- A preview image of a trip's route, with sharp, for share images and thumbnails.
