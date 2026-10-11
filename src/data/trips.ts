import { defineTrips } from '../moments/trips.ts'

// Each trip plays back every located Moment between `start` and `end` (inclusive) on /map.
export const trips = defineTrips([
  {
    end: '2026-10-06',
    id: '2026-national-day-road-trip',
    start: '2026-10-01',
    title: '国庆东北自驾',
  },
  {
    end: '2026-09-16',
    id: '2026-yunnan',
    start: '2026-09-12',
    title: '云南之旅',
  },
  {
    end: '2026-08-03',
    id: '2026-rizhao-solo-trip',
    start: '2026-08-01',
    title: '单人自驾去日照',
  },
])
