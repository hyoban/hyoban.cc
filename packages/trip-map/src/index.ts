// The DOM-free part of the package: the places and trips a site defines, the trip
// built from them and its Moments, its shaping for the player, and the markup the
// player drives, for a page rendered on the server. Safe to import at build time and
// in Node scripts. The map and the player are in `@hyoban/trip-map/client`, the
// preview image in `@hyoban/trip-map/preview`.

export {
  buildFogOfWar,
  type FogOfWar,
  type FogRegion,
  type FogRegions,
  type WeightedPlace,
} from './fog-of-war.ts'
export {
  formatDate,
  formatDateRange,
  getDateParts,
  getTextLang,
  parseDateKey,
  setTextLang,
} from './format.ts'
export { getDistanceKm, toCoordinates, type Coordinates, type LngLatLike } from './geo.ts'
export { renderIcon, type IconName, type WeatherIcon } from './icons.ts'
export * from './locations.ts'
export {
  renderShortcuts,
  renderTripPanel,
  renderTripStage,
  toScriptJson,
  type Brand,
  type MusicTrack,
} from './markup.ts'
export {
  ALL_PLACES_MAX_ZOOM,
  getAllPlacesCamera,
  getAllPlacesPadding,
  getPlacesExtent,
  getStaticMapUrl,
  type StaticMapCamera,
  type StaticMapOptions,
} from './static-map.ts'
export { getPlaceShard, PLACE_SHARDS } from './place-shards.ts'
export { resolveMessages, t, zhCN, type MessageKey, type Messages } from './messages.ts'
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
  getStopEyebrow,
  getStopName,
  pickCovers,
  type TripSlide,
} from './player/slides.ts'
export type {
  MapLabel,
  MapStats,
  PlaceMarker,
  Slide,
  TripData,
  TripDataLeg,
  TripDataStop,
  TripDataWaypoint,
  TripCharging,
  TripDriving,
  TripImage,
  TripImageInput,
  TripPlace,
  TripPoint,
  TripSummary,
  TripWeather,
} from './player/types.ts'
export {
  getMapStats,
  getPlaceMarkers,
  getTripSummary,
  shapeTripData,
  type DayCharging,
  type DayDriving,
  type ShapeTripOptions,
  type StopDetails,
} from './shape.ts'
export { createYearPalette, YEAR_COLORS, type Theme } from './theme.ts'
export * from './trips.ts'
export * from './weather.ts'
