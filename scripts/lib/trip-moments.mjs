import { join } from 'node:path'

import { locations } from '../../src/data/locations.ts'
import { getLocation } from '../../src/moments/map-locations.ts'
import { compareMoments, getMomentOrder } from '../../src/moments/order.ts'
import { readMomentRecords } from './moment-assets.mjs'

export const resolveLocation = (id) => ({ id, ...getLocation(locations, id) })

/** Visible Moments with resolved places, oldest first, as `buildTrip` takes them. */
export async function readTripMoments(root) {
  return (await readMomentRecords(join(root, 'src/content/moments')))
    .filter(({ moment }) => !moment.hidden)
    .map(({ id, moment }) => {
      const dateKey = moment.occurredAt.slice(0, 10)

      return {
        dateKey,
        id,
        locations: [moment.location ?? []].flat().map(resolveLocation),
        occurredAt: moment.occurredAt,
        order: getMomentOrder(id, dateKey),
      }
    })
    .sort(compareMoments)
}
