import assert from 'node:assert/strict'
import { test } from 'vite-plus/test'
import {
  getBasemapScheme,
  getBasemapStyle,
  getPitchAtZoom,
  isMapStyleId,
  STANDARD_STYLE,
} from '../src/map/basemap.ts'
import { DEFAULT_MAP_SETTINGS, getLightAtTime, parseMapSettings } from '../src/map/map-settings.ts'

test('gives the settings their style, Standard its own and how far it leans', () => {
  const faded = getBasemapStyle({ ...DEFAULT_MAP_SETTINGS, style: 'faded' }, true)
  assert.equal(faded.url, STANDARD_STYLE)
  assert.equal(faded.pitch, 50)
  assert.equal(faded.config.basemap.lightPreset, 'night')
  assert.equal(faded.config.basemap.theme, 'faded')
  // As plain as the flat map, by default.
  assert.equal(faded.config.basemap.showPointOfInterestLabels, false)
  assert.equal(faded.config.basemap.showRoadLabels, true)

  const standard = getBasemapStyle(
    { ...DEFAULT_MAP_SETTINGS, buildings: false, light: 'dusk', pois: true, tilt: false },
    false,
  )
  assert.equal(standard.config.basemap.theme, 'default')
  assert.equal(standard.config.basemap.lightPreset, 'dusk')
  assert.equal(standard.config.basemap.show3dObjects, false)
  assert.equal(standard.config.basemap.showPointOfInterestLabels, true)
  assert.equal(standard.pitch, 0)

  assert.deepEqual(getBasemapStyle({ ...DEFAULT_MAP_SETTINGS, style: 'classic' }, true), {
    config: undefined,
    pitch: 0,
    url: 'mapbox://styles/mapbox/dark-v11',
  })
})

test('lights the map by the clock, and the page dark with it at dusk and by night', () => {
  const at = (hour) => new Date(2026, 9, 23, hour, 30)
  assert.deepEqual(
    [0, 4, 5, 6, 7, 12, 17, 18, 19, 20, 23].map((hour) => getLightAtTime(at(hour))),
    ['night', 'night', 'dawn', 'dawn', 'day', 'day', 'day', 'dusk', 'dusk', 'night', 'night'],
  )

  const time = { ...DEFAULT_MAP_SETTINGS, light: 'time' }
  assert.equal(getBasemapStyle(time, true, at(6)).config.basemap.lightPreset, 'dawn')
  assert.equal(getBasemapScheme(time, at(12)), 'light')
  assert.equal(getBasemapScheme(time, at(18)), 'dark')
  // The page keeps its own scheme otherwise.
  assert.equal(getBasemapScheme(DEFAULT_MAP_SETTINGS, at(23)), undefined)
  assert.equal(getBasemapScheme({ ...time, style: 'classic' }, at(23)), undefined)
})

test('reads stored settings, leaving what it does not understand at its default', () => {
  assert.deepEqual(parseMapSettings(null), DEFAULT_MAP_SETTINGS)
  assert.deepEqual(
    parseMapSettings({ light: 'noon', pois: 'yes', projection: 'mercator', style: 'monochrome' }),
    { ...DEFAULT_MAP_SETTINGS, projection: 'mercator', style: 'monochrome' },
  )
})

test('tells a look by its name', () => {
  assert.equal(isMapStyleId('monochrome'), true)
  assert.equal(isMapStyleId('satellite'), false)
  assert.equal(isMapStyleId(null), false)
})

test('leans further the closer the map is', () => {
  assert.equal(getPitchAtZoom(2), 0)
  assert.equal(getPitchAtZoom(3), 0)
  assert.equal(getPitchAtZoom(7.5), 30)
  assert.equal(getPitchAtZoom(13), 50)
  assert.equal(getPitchAtZoom(18), 50)
  // A map that leans no further than flat stays flat.
  assert.equal(getPitchAtZoom(12, 0), 0)
})
