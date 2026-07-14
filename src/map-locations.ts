export type MapLocation = Readonly<{
  latitude: number
  longitude: number
  name: string
}>

type MappableMoment = Readonly<{
  dateKey: string
  locationId?: string
  publishedAt: Date
}>

export type MapLocationGroup<T extends MappableMoment> = Readonly<{
  id: string
  latestYear: number
  location: MapLocation
  moments: T[]
}>

const YEAR_COLORS = [
  '#3b82f6',
  '#8b5cf6',
  '#f59e0b',
  '#10b981',
  '#ef4444',
  '#06b6d4',
] as const

export function defineLocations<const T extends Record<string, MapLocation>>(locations: T) {
  for (const [id, location] of Object.entries(locations)) {
    const validLatitude = Number.isFinite(location.latitude)
      && location.latitude >= -90
      && location.latitude <= 90
    const validLongitude = Number.isFinite(location.longitude)
      && location.longitude >= -180
      && location.longitude <= 180

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
    if (!moment.locationId) {
      continue
    }

    getLocation(locations, moment.locationId)
    const group = momentGroups.get(moment.locationId) ?? []
    group.push(moment)
    momentGroups.set(moment.locationId, group)
  }

  return [...momentGroups]
    .map(([id, groupMoments]): MapLocationGroup<T> => {
      groupMoments.sort(compareMomentsDescending)
      const newestMoment = groupMoments[0]!

      return {
        id,
        latestYear: Number.parseInt(newestMoment.dateKey.slice(0, 4), 10),
        location: getLocation(locations, id),
        moments: groupMoments,
      }
    })
    .sort((first, second) => compareMomentsDescending(first.moments[0]!, second.moments[0]!))
}

export function getYearColor(year: number) {
  const index = ((year - 2024) % YEAR_COLORS.length + YEAR_COLORS.length) % YEAR_COLORS.length
  return YEAR_COLORS[index]!
}

function compareMomentsDescending(first: MappableMoment, second: MappableMoment) {
  return second.dateKey.localeCompare(first.dateKey)
    || second.publishedAt.valueOf() - first.publishedAt.valueOf()
}
