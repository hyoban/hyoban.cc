import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'

import { findPois, gcjToWgs, keptPois, kindOf, readPoiQuery, wgsToGcj } from '../src/pois.ts'
import { call, setUp, token } from './setup.mjs'

// The app's place search in China: Tencent's places, asked for in GCJ-02 and given back in
// WGS 84. Tencent is faked; the places are made up.

const owner = { auth: token }
const here = { latitude: 31.2, longitude: 121.4 }

function tencent(answer, asked = []) {
  return async (url) => {
    asked.push(new URL(url))
    return Response.json(answer)
  }
}

test('what is around a point comes from explore, shifted there and back', async () => {
  const shifted = wgsToGcj({ latitude: 31.2001, longitude: 121.4001 })
  const asked = []
  const pois = await findPois(
    here,
    'key',
    tencent(
      {
        status: 0,
        data: [
          {
            title: '某某面馆(某商场店)',
            address: '某路 1 号',
            category: '美食:中餐厅:面馆',
            location: { lat: shifted.latitude, lng: shifted.longitude },
            _distance: 15,
          },
          { title: '某某大厦', category: '房产小区:商务楼宇', location: { lat: 31.2, lng: 121.4 } },
          { title: '', location: { lat: 31.2, lng: 121.4 } },
        ],
      },
      asked,
    ),
  )

  assert.equal(asked[0].pathname, '/ws/place/v1/explore')
  assert.match(asked[0].searchParams.get('boundary'), /^nearby\(31\.19\d+,121\.40\d+,400,0\)$/)
  assert.equal(pois.length, 2)
  assert.deepEqual(pois[0], {
    name: '某某面馆(某商场店)',
    latitude: 31.2001,
    longitude: 121.4001,
    address: '某路 1 号',
    kind: 'food',
    distance: 15,
  })
  assert.equal(pois[1].kind, undefined)
})

test('words are looked for with suggestion, from the point', async () => {
  const asked = []

  await findPois({ ...here, text: '面馆' }, 'key', tencent({ status: 0, data: [] }, asked))

  assert.equal(asked[0].pathname, '/ws/place/v1/suggestion')
  assert.equal(asked[0].searchParams.get('keyword'), '面馆')
  assert.ok(asked[0].searchParams.get('location'))
})

test('outside China nothing is asked', async () => {
  const pois = await findPois({ latitude: 35.68, longitude: -0.1 }, 'key', async () => {
    throw new Error('Tencent should not be asked')
  })

  assert.deepEqual(pois, [])
})

test("Tencent's calls used up are a 503, so the app asks Photon", async () => {
  await assert.rejects(
    findPois(here, 'key', tencent({ status: 121, message: '此key每日调用量已达到上限' })),
    (caught) => caught.status === 503,
  )
})

test('the endpoint needs the key and a point', async () => {
  const { env } = setUp()

  assert.equal((await call(env, 'GET', '/v2/pois?lat=31.2&lon=121.4', owner)).status, 503)

  const keyed = { ...env, TENCENT_MAP_KEY: 'key' }

  assert.equal((await call(keyed, 'GET', '/v2/pois?lat=north&lon=121.4', owner)).status, 400)
  assert.equal(
    (await call(keyed, 'GET', '/v2/pois?lat=31.2&lon=121.4', { auth: null })).status,
    401,
  )
})

test('an answer is kept a day, so a pin moved back costs no call', async () => {
  const asked = []
  const fetcher = tencent({ status: 0, data: [] }, asked)
  const query = { latitude: 30.5, longitude: 114.3 }

  await keptPois(query, 'key', fetcher, 0)
  await keptPois(query, 'key', fetcher, 60_000)
  assert.equal(asked.length, 1)
  await keptPois(query, 'key', fetcher, 25 * 60 * 60 * 1000)
  assert.equal(asked.length, 2)
})

test('a query is a point to about 100 m and its words', () => {
  assert.deepEqual(readPoiQuery(new URLSearchParams('lat=31.20012&lon=121.39987&q=%20面馆%20')), {
    ...here,
    text: '面馆',
  })
  assert.ok('error' in readPoiQuery(new URLSearchParams('lat=91&lon=121.4')))
  assert.ok('error' in readPoiQuery(new URLSearchParams('lon=121.4')))
})

test("Tencent's categories say what kind of place it is", () => {
  assert.equal(kindOf('购物:综合商场:购物中心'), 'shopping')
  assert.equal(kindOf('酒店宾馆:星级酒店'), 'stay')
  assert.equal(kindOf('旅游景点:公园'), 'scenic')
  assert.equal(kindOf('基础设施:交通设施:地铁站'), 'transport')
  assert.equal(kindOf('汽车:充电站'), 'transport')
  assert.equal(kindOf('娱乐休闲:咖啡厅'), 'food')
  assert.equal(kindOf('娱乐休闲:KTV'), undefined)
})

test('GCJ-02 shifted back lands within a centimetre', () => {
  const back = gcjToWgs(wgsToGcj(here))

  assert.ok(Math.abs(back.latitude - here.latitude) < 1e-7)
  assert.ok(Math.abs(back.longitude - here.longitude) < 1e-7)
})
