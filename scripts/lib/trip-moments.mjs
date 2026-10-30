import { compareMoments, getLocation } from '@hyoban/trip-map'

import { locations } from '../../apps/web/src/data/locations.ts'
import { readMomentRecords } from './moment-assets.mjs'

export const resolveLocation = (id) => ({ id, ...getLocation(locations, id) })

/** Visible Moments with resolved places, oldest first, as `buildTrip` takes them. */
export async function readTripMoments() {
  return (await readMomentRecords())
    .filter(({ moment }) => !moment.hidden)
    .map(({ id, moment, record }) => ({
      dateKey: moment.occurredAt.slice(0, 10),
      id,
      locations: (moment.location ?? []).map(resolveLocation),
      occurredAt: moment.occurredAt,
      order: record.order,
      uid: record.uid,
    }))
    .sort(compareMoments)
}
