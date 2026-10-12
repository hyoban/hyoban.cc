// The browser side: the map page with its places and trips, and the trip player. It
// brings in mapbox-gl, so load it on the map page only, and after the page has painted.

export type { LightboxMedia } from './map/place-drawer.ts'
export type { PlaceMarker } from './map/place-markers.ts'
export {
  captureMap,
  savePicture,
  type ScreenshotOptions,
  type ScreenshotPlace,
} from './map/screenshot.ts'
export { defaultUrls, type Urls } from './map/selection-url.ts'
export { destroyTripMap, initializeTripMap, type TripMapOptions } from './map/trip-map.ts'
export { createTripOverview } from './player/overview.ts'
export { createTripPlayer, type TripPlayerOptions } from './player/player.ts'
export { readTheme } from './theme.ts'
