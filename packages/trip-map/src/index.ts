// The DOM-free part of the package: the places and trips a site defines, the trip
// built from them and its Moments, and the helpers that shape it for the player. Safe
// to import at build time and in Node scripts. The map and the player themselves are
// in `@hyoban/trip-map/client`, the preview image in `@hyoban/trip-map/preview`.

export { type Coordinates, getDistanceKm as getCoordinateDistanceKm } from './geo.ts'
export * from './locations.ts'
export { getIntroCovers, getTripMeta, MOSAIC_CLASS, MOSAIC_IMAGE_CLASS } from './player/intro.ts'
export {
  createChapterLog,
  formatChapterTime,
  getShortPlaceName,
  getSummaryStats,
} from './player/labels.ts'
export {
  easeInOut,
  getRouteLine,
  getTravelMode,
  measureLine,
  simplifyLine,
  walkLine,
  type MeasuredLine,
} from './player/route.ts'
export {
  buildSlides,
  getSlideDuration,
  getSlideLabel,
  pickCovers,
  type TripSlide,
} from './player/slides.ts'
export type {
  MapLabel,
  Slide,
  TripData,
  TripDataStop,
  TripDataWaypoint,
  TripImage,
  TripPlace,
  TripPoint,
  TripSummary,
  TripWeather,
} from './player/types.ts'
export * from './trips.ts'
export * from './weather.ts'
