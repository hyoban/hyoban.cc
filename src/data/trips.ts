import { defineTrips } from '../moments/trips.ts'

// Each trip plays back every located Moment between `start` and `end` (inclusive) at
// /trip/<id>/. `waypoints` add places the route passed through without a Moment.
// After adding a trip or moving a place, run `pnpm trips:routes` so the route follows roads.
export const trips = defineTrips([
  {
    covers: [
      '2026/10/01-02-ferry-sunset/ferry-sunset-live-6.webp',
      '2026/10/02-02-dalian-morning/dalian-morning-live-6.webp',
      '2026/10/03-01-dandong-morning/dandong-morning-live-3.webp',
      '2026/10/04-02-shenyang-cybercab/shenyang-cybercab-6.webp',
      '2026/10/02-03-bangchuidao-seaside/bangchuidao-seaside-live-3.webp',
      '2026/10/01-01-yantai-dalian-ferry/yantai-dalian-ferry-live-3.webp',
      '2026/10/02-03-bangchuidao-seaside/bangchuidao-seaside-live-5.webp',
      '2026/10/03-02-dandong-soup-rice/dandong-soup-rice-live-1.webp',
      '2026/10/05-01-tianjin-guobacai/tianjin-guobacai-6.webp',
    ],
    end: '2026-10-06',
    id: '2026-national-day-road-trip',
    start: '2026-09-30',
    straightLegs: [
      ['yantai-port-passenger-terminal', 'bohai-sea', 'ferry'],
      ['bohai-sea', 'dalian-huozhang-niaogengzuo', 'ferry'],
    ],
    title: '国庆东北自驾',
    waypoints: [
      { at: '2026-09-30T16:00:00+08:00', location: 'wuwei' },
      { at: '2026-09-30T18:00:00+08:00', location: 'nanjing' },
    ],
  },
  {
    covers: [
      '2026/09/14-01-tiger-leaping-gorge/tiger-leaping-gorge-live-2.webp',
      '2026/09/16-01-shuhe-and-zhongyi-market/shuhe-and-zhongyi-market-live-3.webp',
      '2026/09/16-01-shuhe-and-zhongyi-market/shuhe-and-zhongyi-market-live-1.webp',
      '2026/09/14-01-tiger-leaping-gorge/tiger-leaping-gorge-live-4.webp',
      '2026/09/16-01-shuhe-and-zhongyi-market/shuhe-and-zhongyi-market-live-5.webp',
      '2026/09/16-01-shuhe-and-zhongyi-market/shuhe-and-zhongyi-market-live-4.webp',
      '2026/09/16-01-shuhe-and-zhongyi-market/shuhe-and-zhongyi-market-live-6.webp',
      '2026/09/14-01-tiger-leaping-gorge/tiger-leaping-gorge-live-1.webp',
      '2026/09/13-02-yunnan-birthday-surprise/yunnan-birthday-surprise.webp',
    ],
    end: '2026-09-16',
    id: '2026-yunnan',
    music: 'apple-cider',
    sameWayBack: [['lijiang', 'lugu-lake']],
    start: '2026-09-12',
    straightLegs: [['nanjing-lukou-airport', 'lijiang-sanyi-airport', 'plane']],
    title: '云南之旅',
    // The morning's flight from Hefei broke down, so the trip flew from Nanjing instead.
    waypoints: [
      { at: '2026-09-12T17:15:00+08:00', location: 'nanjing-lukou-airport' },
      { location: 'lijiang-sanyi-airport' },
      // Lugu Lake was a day out from Lijiang and back the same way.
      { at: '2026-09-15T08:00:00+08:00', location: 'lijiang' },
      { at: '2026-09-15T23:50:00+08:00', location: 'lijiang' },
      { at: '2026-09-16T13:25:00+08:00', location: 'lijiang-sanyi-airport' },
    ],
  },
  {
    covers: [
      '2026/08/03-01-rizhao-sunrise/rizhao-sunrise-video-2-poster.webp',
      '2026/08/01-02-huaian/huaian-7.webp',
      '2026/08/02-01-liandao/liandao-3.webp',
      '2026/08/03-01-rizhao-sunrise/rizhao-sunrise.webp',
      '2026/08/01-02-huaian/huaian-8.webp',
      '2026/08/02-03-rizhao-food/rizhao-food-3.webp',
      '2026/08/02-01-liandao/liandao-9.webp',
      '2026/08/01-02-huaian/huaian-4.webp',
      '2026/08/02-01-liandao/liandao.webp',
    ],
    end: '2026-08-03',
    id: '2026-rizhao-solo-trip',
    start: '2026-08-01',
    title: '单人自驾去日照',
  },
  {
    end: '2026-06-20',
    id: '2026-dragon-boat-laojunshan',
    start: '2026-06-19',
    title: '端午自驾老君山',
    waypoints: [{ at: '2026-06-19T08:00:00+08:00', location: 'wuwei' }],
  },
  {
    end: '2026-05-05',
    id: '2026-may-day-road-trip',
    start: '2026-05-01',
    title: '五一皖赣自驾',
    waypoints: [{ at: '2026-05-01T09:00:00+08:00', location: 'wuwei' }],
  },
  {
    end: '2026-04-05',
    id: '2026-qingming-anji',
    start: '2026-04-04',
    title: '清明安吉床车',
    waypoints: [{ at: '2026-04-04T13:00:00+08:00', location: 'wuwei' }],
  },
  {
    end: '2025-11-05',
    id: '2025-western-sichuan-road-trip',
    start: '2025-10-18',
    title: '床车自驾川西',
    // Set out from Wuhu, resting a week in Xiangyang on the way.
    waypoints: [{ at: '2025-10-18T10:00:00+08:00', location: 'wuwei' }],
  },
  {
    end: '2024-11-30',
    id: '2024-code-and-travel',
    start: '2024-09-03',
    // No car yet: every leg went by high-speed rail or plane.
    straightLegs: [
      ['beijing', 'qingdao', 'train'],
      ['qingdao', 'nanjing', 'train'],
      ['nanjing', 'chengdu', 'plane'],
      ['chengdu', 'changsha', 'train'],
      ['changsha', 'shenzhen', 'train'],
      ['shenzhen', 'hong-kong', 'train'],
      ['hong-kong', 'guangzhou', 'train'],
    ],
    title: '边写代码边旅游',
  },
])
