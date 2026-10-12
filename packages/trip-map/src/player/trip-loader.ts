import type { TripData, TripSummary } from './types.ts'

// The trips the player can open: their names from the overview, and each one's
// playback data, inlined for the trip the page opens on and fetched for the others.

export type TripLoader = ReturnType<typeof createTripLoader>

export function createTripLoader({
  fetchTrip,
  inline,
  trips,
}: {
  /** Fetches a trip's `TripData`; undefined when it can't be had. */
  fetchTrip: (id: string) => Promise<TripData | undefined>
  /** The trip whose data the page already carries. */
  inline?: TripData | undefined
  trips: readonly TripSummary[]
}) {
  const titles = new Map(trips.map((trip) => [trip.id, trip.title]))
  const loaded = new Map<string, TripData>()
  const requests = new Map<string, Promise<TripData | undefined>>()

  if (inline) {
    loaded.set(inline.id, inline)
  }

  /** Fetches a trip's data once; a failed fetch is tried again next time. */
  function load(id: string) {
    let request = requests.get(id)

    if (!request) {
      request = fetchTrip(id)
        .catch(() => undefined)
        .then((data) => {
          if (data) {
            loaded.set(id, data)
          } else {
            requests.delete(id)
          }

          return data
        })
      requests.set(id, request)
    }

    return request
  }

  return {
    /** The data already here, if it is. */
    get: (id: string) => loaded.get(id),
    getTitle: (id: string) => titles.get(id),
    has: (id: string) => titles.has(id),
    load,
    /** Loads a trip's data ahead of time, when it looks likely to be opened next. */
    prefetch(id: string) {
      if (titles.has(id) && !loaded.has(id)) {
        void load(id)
      }
    },
  }
}
