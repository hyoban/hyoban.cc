export type MapLocation = Readonly<{
  latitude: number
  longitude: number
  name: string
}>

type MappableMoment = Readonly<{
  dateKey: string
  locations: readonly { id: string }[]
  occurredAt: string
  order: number
}>

export type MapLocationGroup<T extends MappableMoment> = Readonly<{
  id: string
  firstYear: number
  location: MapLocation
  moments: T[]
}>

export function defineLocations<const T extends Record<string, MapLocation>>(locations: T) {
  for (const [id, location] of Object.entries(locations)) {
    const validLatitude =
      Number.isFinite(location.latitude) && location.latitude >= -90 && location.latitude <= 90
    const validLongitude =
      Number.isFinite(location.longitude) && location.longitude >= -180 && location.longitude <= 180

    if (!validLatitude || !validLongitude) {
      throw new RangeError(`Invalid coordinates for location "${id}".`)
    }
  }

  return locations
}

export function getLocation<T extends Record<string, MapLocation>>(locations: T, id: string) {
  const location = locations[id]

  if (!location) {
    throw new Error(`Unknown location id "${id}".`)
  }

  return location
}

export function groupMomentsByLocation<T extends MappableMoment>(
  locations: Record<string, MapLocation>,
  moments: T[],
) {
  const momentGroups = new Map<string, T[]>()

  for (const moment of moments) {
    for (const { id } of moment.locations) {
      getLocation(locations, id)
      const group = momentGroups.get(id) ?? []
      group.push(moment)
      momentGroups.set(id, group)
    }
  }

  return [...momentGroups]
    .map(([id, groupMoments]): MapLocationGroup<T> => {
      groupMoments.sort(compareMomentsDescending)
      // Colored by the year the place first appears, so a revisit keeps its color.
      const oldestMoment = groupMoments.at(-1)!

      return {
        id,
        firstYear: Number.parseInt(oldestMoment.dateKey.slice(0, 4), 10),
        location: getLocation(locations, id),
        moments: groupMoments,
      }
    })
    .sort((first, second) => compareMomentsDescending(first.moments[0]!, second.moments[0]!))
}

/** Oldest first; timed Moments on the same day sort by time, then by `order`. */
export function compareMoments(first: MappableMoment, second: MappableMoment) {
  const dateComparison = first.dateKey.localeCompare(second.dateKey)

  if (dateComparison !== 0) {
    return dateComparison
  }

  if (first.occurredAt.length > 10 && second.occurredAt.length > 10) {
    return (
      Date.parse(first.occurredAt) - Date.parse(second.occurredAt) || first.order - second.order
    )
  }

  return first.order - second.order
}

function compareMomentsDescending(first: MappableMoment, second: MappableMoment) {
  return compareMoments(second, first)
}
