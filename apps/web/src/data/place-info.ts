import type { Place, PlaceKind } from '@hyoban/data-spec'

// What the places' kind and division read as on the pages.

export const placeKindLabels: Record<PlaceKind, string> = {
  city: '城市',
  town: '城镇乡村',
  region: '区域',
  food: '餐饮',
  cafe: '咖啡',
  tea: '茶饮',
  bar: '酒吧',
  dessert: '甜品烘焙',
  shopping: '购物',
  supermarket: '超市便利店',
  market: '市集',
  stay: '住宿',
  camp: '露营',
  scenic: '景点',
  park: '公园',
  museum: '文化场馆',
  temple: '寺庙教堂',
  nature: '山水自然',
  leisure: '娱乐休闲',
  show: '影院演出',
  sport: '运动健身',
  transport: '交通',
  station: '火车站',
  metro: '地铁站',
  bus: '公交车站',
  airport: '机场',
  port: '码头',
  parking: '停车场',
  charging: '充电站',
  fuel: '加油站',
  life: '生活服务',
  hospital: '医院',
  school: '学校',
  work: '公司园区',
  residential: '住宅小区',
}

/** Its division's two most particular names, `合肥市 肥西县`; Beijing's city doesn't repeat its province. */
export function getDivisionLabel(division: NonNullable<Place['division']>) {
  return [...new Set([division.province, division.city, division.district])]
    .filter((name) => name !== undefined)
    .slice(-2)
    .join(' ')
}

/** What and where a place is, `景点 合肥市 肥西县`; undefined when it has neither. */
export function getPlaceDetail(place: Place) {
  const parts = [
    place.kind && placeKindLabels[place.kind],
    place.division && getDivisionLabel(place.division),
  ].filter(Boolean)

  return parts.length > 0 ? parts.join(' ') : undefined
}
