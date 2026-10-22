import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import { getAllPlacesCamera, getPlacesExtent, getStaticMapUrl } from '../src/static-map.ts'

const places = [
  { latitude: 39.9, longitude: 116.4 },
  { latitude: 22.5, longitude: 114.1 },
]

test('frames every place between the toolbar and the legend', () => {
  const camera = getAllPlacesCamera(places, { height: 800, narrow: false, width: 1280 })

  // Tall more than wide, so the height decides the zoom.
  assert.ok(camera.zoom > 3 && camera.zoom < 5)
  assert.ok(Math.abs(camera.longitude - 115.25) < 0.01)
  // North of the places' middle, as the toolbar takes more room than the legend.
  const { maxY, minY } = getPlacesExtent(places)
  assert.ok(camera.y < (minY + maxY) / 2)
})

test('frames a single place no closer than a city', () => {
  assert.equal(
    getAllPlacesCamera(places.slice(0, 1), { height: 800, narrow: false, width: 1280 }).zoom,
    10,
  )
})

test('asks the Static Images API for the camera, without its logo', () => {
  const url = new URL(
    getStaticMapUrl(
      { latitude: 31, longitude: 115, zoom: 3.5 },
      { height: 600, style: 'mapbox/light-v11', token: 'pk.test', width: 800 },
    ),
  )

  assert.equal(url.pathname, '/styles/v1/mapbox/light-v11/static/115.00000,31.00000,3.50/800x600')
  assert.equal(url.searchParams.get('logo'), 'false')
  assert.equal(url.searchParams.get('access_token'), 'pk.test')
})
