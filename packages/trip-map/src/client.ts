// The browser side: the map page with its places and trips, and the trip player. It
// brings in mapbox-gl, so load it on the map page only, and after the page has painted.

export {
  destroyCalendarMap,
  initializeCalendarMap,
  type CalendarMapOptions,
  type LightboxMedia,
} from './calendar-map.ts'
export { createTripOverview } from './player/overview.ts'
export { createTripPlayer } from './player/player.ts'
export {
  captureMap,
  savePicture,
  type ScreenshotOptions,
  type ScreenshotPlace,
} from './screenshot.ts'
