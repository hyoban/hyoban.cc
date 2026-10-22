// The browser side: the map page with its places and trips, and the trip player. It
// brings in mapbox-gl, so load it on the map page only, and after the page has painted.

export type { LightboxMedia } from './map/place-drawer.ts'
export {
  captureMap,
  savePicture,
  type ScreenshotOptions,
  type ScreenshotPlace,
} from './map/screenshot.ts'
export type { Period } from './map/period.ts'
export { defaultUrls, type Urls } from './map/selection-url.ts'
export {
  createTripMap,
  type TripMap,
  type TripMapError,
  type TripMapOptions,
} from './map/trip-map.ts'
export { createTripOverview } from './player/overview.ts'
export { createTripPlayer, type TripPlayerOptions } from './player/player.ts'
export type { MapStats, PlaceMarker, TripData, TripSummary } from './player/types.ts'
export { readTheme } from './dom-theme.ts'
