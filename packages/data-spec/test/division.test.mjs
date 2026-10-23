import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { createDataVChildren, locateDivision, toGcj02 } from '../src/division.ts'

// The divisions as DataV draws them, in GCJ-02, here as squares around the shifted
// points: a province, a city in it and a district in that, and Beijing whole.

const wuhu = { latitude: 31.35, longitude: 118.43 }
const beijing = { latitude: 39.9, longitude: 116.4 }

function square([x, y], half) {
  return {
    type: 'Polygon',
    coordinates: [
      [
        [x - half, y - half],
        [x + half, y - half],
        [x + half, y + half],
        [x - half, y + half],
        [x - half, y - half],
      ],
    ],
  }
}

const around = (place, half) => square(toGcj02(place.longitude, place.latitude), half)
const feature = (adcode, name, geometry) => ({
  type: 'Feature',
  properties: { adcode, name },
  geometry,
})
const collections = {
  100000: [
    feature(340000, '安徽省', around(wuhu, 2)),
    feature(110000, '北京市', around(beijing, 1)),
    feature('100000_JD', '', around(wuhu, 50)),
  ],
  340000: [feature(340200, '芜湖市', around(wuhu, 0.5))],
  340200: [feature(340202, '镜湖区', around(wuhu, 0.05))],
  110000: [feature(110101, '东城区', around(beijing, 0.1))],
}
const childrenOf = createDataVChildren('https://datav.test', async (url) => {
  const code = url.match(/(\d+)_full\.json$/)[1]
  return { features: collections[code] ?? [] }
})

test('a place is in its province, city and district; a city stops at the city and a region at its province', async () => {
  assert.deepEqual(await locateDivision(wuhu, childrenOf), {
    code: '340202',
    province: '安徽省',
    city: '芜湖市',
    district: '镜湖区',
  })
  assert.deepEqual(await locateDivision({ ...wuhu, kind: 'city' }, childrenOf), {
    code: '340200',
    province: '安徽省',
    city: '芜湖市',
  })
  assert.deepEqual(await locateDivision({ ...wuhu, kind: 'region' }, childrenOf), {
    code: '340000',
    province: '安徽省',
  })
})

test('a municipality is its own city, its districts right under it', async () => {
  assert.deepEqual(await locateDivision(beijing, childrenOf), {
    code: '110101',
    province: '北京市',
    city: '北京市',
    district: '东城区',
  })
})

test('a point just off the edge takes the division nearby, and one far out at sea none', async () => {
  // About 1 km east of the district's square.
  const pier = { latitude: wuhu.latitude, longitude: wuhu.longitude + 0.06 }

  assert.equal((await locateDivision(pier, childrenOf))?.district, '镜湖区')
  assert.equal(await locateDivision({ latitude: 31.35, longitude: 125 }, childrenOf), undefined)
})

test('the children are read once each, and a division without any has none', async () => {
  const read = []
  const counting = createDataVChildren('https://datav.test', async (url) => {
    read.push(url)
    return { features: [] }
  })

  await counting('340200')
  await counting('340200')

  assert.deepEqual(read, ['https://datav.test/340200_full.json'])
  assert.deepEqual(await counting('441900'), [])
})
