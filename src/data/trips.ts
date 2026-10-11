import { defineTrips } from '../moments/trips.ts'

// Each trip plays back every located Moment between `start` and `end` (inclusive) at
// /trip/<id>/. `waypoints` add places the route passed through without a Moment.
// After adding a trip or moving a place, run `pnpm trips:routes` so the route follows roads.
export const trips = defineTrips([
  {
    end: '2026-10-06',
    id: '2026-national-day-road-trip',
    start: '2026-09-30',
    // The Yantai–Dalian ferry.
    straightLegs: [
      ['yantai-port-passenger-terminal', 'bohai-sea'],
      ['bohai-sea', 'dalian-huozhang-niaogengzuo'],
    ],
    title: '国庆东北自驾',
    waypoints: [
      { at: '2026-09-30T16:00:00+08:00', location: 'wuwei' },
      { at: '2026-09-30T18:00:00+08:00', location: 'nanjing' },
    ],
  },
  {
    end: '2026-09-16',
    id: '2026-yunnan',
    start: '2026-09-12',
    // The flight from Hefei to Lijiang.
    straightLegs: [['hefei-xinqiao-airport', 'lijiang']],
    title: '云南之旅',
  },
  {
    end: '2026-08-03',
    id: '2026-rizhao-solo-trip',
    start: '2026-08-01',
    title: '单人自驾去日照',
  },
])
