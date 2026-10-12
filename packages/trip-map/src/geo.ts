// Geometry shared by the build (trip distances) and the trip player (camera and route).

/** A `[longitude, latitude]` pair, as GeoJSON and Mapbox use. */
export type Coordinates = readonly [number, number]

const EARTH_RADIUS_KM = 6371

/** Great-circle distance, so it is a lower bound for the distance actually travelled. */
export function getDistanceKm(from: Coordinates, to: Coordinates) {
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180
  const latitudeDelta = toRadians(to[1] - from[1])
  const longitudeDelta = toRadians(to[0] - from[0])
  const a =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(toRadians(from[1])) * Math.cos(toRadians(to[1])) * Math.sin(longitudeDelta / 2) ** 2

  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a))
}
