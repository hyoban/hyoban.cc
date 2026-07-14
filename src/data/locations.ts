import { defineLocations } from '@/map-locations'

// Public, approximate place centres for the calendar map.
export const locations = defineLocations({
  anji: {
    latitude: 30.638,
    longitude: 119.68,
    name: '安吉',
  },
  beijing: {
    latitude: 39.9042,
    longitude: 116.4074,
    name: '北京',
  },
  chengdu: {
    latitude: 30.5728,
    longitude: 104.0668,
    name: '成都',
  },
  chengkan: {
    latitude: 29.92,
    longitude: 118.283,
    name: '呈坎',
  },
  changsha: {
    latitude: 28.2282,
    longitude: 112.9388,
    name: '长沙',
  },
  guangzhou: {
    latitude: 23.1291,
    longitude: 113.2644,
    name: '广州',
  },
  haiyan: {
    latitude: 30.5254,
    longitude: 120.9458,
    name: '海盐',
  },
  hangzhou: {
    latitude: 30.2741,
    longitude: 120.1551,
    name: '杭州',
  },
  hefei: {
    latitude: 31.8206,
    longitude: 117.2272,
    name: '合肥',
  },
  'hong-kong': {
    latitude: 22.3193,
    longitude: 114.1694,
    name: '香港',
  },
  kangding: {
    latitude: 30.0507,
    longitude: 101.9601,
    name: '康定',
  },
  laojunshan: {
    latitude: 33.758,
    longitude: 111.641,
    name: '老君山',
  },
  lingbi: {
    latitude: 33.541,
    longitude: 117.552,
    name: '灵璧',
  },
  mianyang: {
    latitude: 31.4675,
    longitude: 104.6796,
    name: '绵阳',
  },
  moganshan: {
    latitude: 30.601,
    longitude: 119.868,
    name: '莫干山',
  },
  nanjing: {
    latitude: 32.0603,
    longitude: 118.7969,
    name: '南京',
  },
  qingdao: {
    latitude: 36.0671,
    longitude: 120.3826,
    name: '青岛',
  },
  sanhe: {
    latitude: 31.514,
    longitude: 117.251,
    name: '三河古镇',
  },
  sanqingshan: {
    latitude: 28.917,
    longitude: 118.065,
    name: '三清山',
  },
  shenzhen: {
    latitude: 22.5431,
    longitude: 114.0579,
    name: '深圳',
  },
  suzhou: {
    latitude: 31.2989,
    longitude: 120.5853,
    name: '苏州',
  },
  wuhu: {
    latitude: 31.3529,
    longitude: 118.4331,
    name: '芜湖',
  },
  wuyuan: {
    latitude: 29.248,
    longitude: 117.861,
    name: '婺源',
  },
  xiangyang: {
    latitude: 32.009,
    longitude: 112.1224,
    name: '襄阳',
  },
  xinyang: {
    latitude: 32.147,
    longitude: 114.091,
    name: '信阳',
  },
  xuchang: {
    latitude: 34.0357,
    longitude: 113.8523,
    name: '许昌',
  },
  zhenjiang: {
    latitude: 32.188,
    longitude: 119.424,
    name: '镇江',
  },
})

export type LocationId = keyof typeof locations

export function isLocationId(value: string): value is LocationId {
  return Object.hasOwn(locations, value)
}
