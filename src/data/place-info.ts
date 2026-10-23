import type { Place, PlaceKind } from '@hyoban/data-spec'

// What the places' kind and division read as on the pages.

export const placeKindLabels: Record<PlaceKind, string> = {
  city: '城市',
  town: '城镇乡村',
  region: '区域',
  scenic: '景点',
  food: '餐饮',
  shopping: '购物',
  stay: '住宿',
  transport: '交通',
}

/** Its division's two most particular names, `合肥市 肥西县`; Beijing's city doesn't repeat its province. */
export function getDivisionLabel(division: NonNullable<Place['division']>) {
  return [...new Set([division.province, division.city, division.district])]
    .filter((name) => name !== undefined)
    .slice(-2)
    .join(' ')
}

/** What and where a place is, `景点 · 合肥市 肥西县`; undefined when it has neither. */
export function getPlaceDetail(place: Place) {
  const parts = [
    place.kind && placeKindLabels[place.kind],
    place.division && getDivisionLabel(place.division),
  ].filter(Boolean)

  return parts.length > 0 ? parts.join(' · ') : undefined
}
