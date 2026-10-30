import { defineLocations } from '@hyoban/trip-map'
import { readPlaces } from './store.ts'

// The places Moments and trips name, from data/places.json: public, approximate centres
// in WGS-84, where Mapbox labels them.
export const locations = defineLocations(readPlaces())

export type LocationId = keyof typeof locations

export function isLocationId(id: string): id is LocationId {
  return Object.hasOwn(locations, id)
}
