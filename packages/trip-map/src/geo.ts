// Geometry shared by the build (trip distances) and the trip player (camera and route).

/** A `[longitude, latitude]` pair, as GeoJSON and Mapbox use. */
export type Coordinates = readonly [number, number]

/** A point on the earth, as a pair or as a place with `latitude` and `longitude`. */
export type LngLatLike = Coordinates | Readonly<{ latitude: number; longitude: number }>

const EARTH_RADIUS_KM = 6371

export function toCoordinates(point: LngLatLike): Coordinates {
  return Array.isArray(point)
    ? (point as Coordinates)
    : [(point as { longitude: number }).longitude, (point as { latitude: number }).latitude]
}

/** Great-circle distance, so it is a lower bound for the distance actually travelled. */
export function getDistanceKm(from: LngLatLike, to: LngLatLike) {
  const [fromLongitude, fromLatitude] = toCoordinates(from)
  const [toLongitude, toLatitude] = toCoordinates(to)
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180
  const latitudeDelta = toRadians(toLatitude - fromLatitude)
  const longitudeDelta = toRadians(toLongitude - fromLongitude)
  const a =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(toRadians(fromLatitude)) *
      Math.cos(toRadians(toLatitude)) *
      Math.sin(longitudeDelta / 2) ** 2

  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a))
}

/** Which way `to` lies from `from` as a flat map lays them out, in degrees from north. */
export function getHeading(from: Coordinates, to: Coordinates) {
  const mercatorY = ([, latitude]: Coordinates) =>
    Math.log(Math.tan(Math.PI / 4 + (latitude * Math.PI) / 360))

  // Mercator's y grows northwards here, as latitude does.
  return (
    (Math.atan2(((to[0] - from[0]) * Math.PI) / 180, mercatorY(to) - mercatorY(from)) * 180) /
    Math.PI
  )
}

/** The turn from `from` to `to` the short way round, in degrees between -180 and 180. */
export function getTurn(from: number, to: number) {
  return ((((to - from) % 360) + 540) % 360) - 180
}
