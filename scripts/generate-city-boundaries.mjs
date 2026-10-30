import { execFileSync } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

// The cities the map's fog of war lights, from Aliyun DataV's GeoAtlas: every
// prefecture-level city, with Beijing, Tianjin, Shanghai, Chongqing, Hong Kong, Macau
// and Taiwan each one region, simplified together by mapshaper so neighbours still
// meet; and the rest of the world as one more region, never lit. The site serves it at
// /map/cities.json and the Android app ships it as an asset.

const root = fileURLToPath(new URL('../', import.meta.url))
const output = join(root, 'apps/web/src/data/boundaries/cities.json')
const source = 'https://geo.datav.aliyun.com/areas_v3/bound'
// Provinces taken whole: their own features are districts.
const WHOLE = new Set(['11', '12', '31', '50', '71', '81', '82'])
const MAPSHAPER = 'mapshaper@0.6.102'
// The share of the boundaries' points kept.
const SIMPLIFY = '30%'
// Web Mercator stops short of the poles, and Mapbox clips a ring on its very edge.
const WORLD_LATITUDE = 85
// The world's tiles, in degrees.
const TILE = 10
// Round China, where the world's tiles are smaller.
const NEAR = { east: 150, north: 60, south: 0, west: 60 }

async function fetchJson(name) {
  const response = await fetch(`${source}/${name}.json`)

  if (!response.ok) {
    throw new Error(`${name}: ${response.status} ${response.statusText}`)
  }

  return response.json()
}

const [cities, provinces] = await Promise.all([
  fetchJson('100000_full_city'),
  fetchJson('100000_full'),
])
const regions = [
  ...cities.features.filter(({ properties }) => {
    const id = String(properties.adcode)
    return !id.endsWith('_JD') && !WHOLE.has(id.slice(0, 2))
  }),
  ...provinces.features.filter(({ properties }) =>
    WHOLE.has(String(properties.adcode).slice(0, 2)),
  ),
].map(({ geometry, properties }) => ({
  geometry,
  properties: { id: String(properties.adcode), name: properties.name },
  type: 'Feature',
}))

const work = await mkdtemp(join(tmpdir(), 'city-boundaries-'))

try {
  const input = join(work, 'regions.json')
  const simplified = join(work, 'cities.json')
  const tiles = join(work, 'tiles.json')
  const outline = join(work, 'outside.json')
  const mapshaper = (...args) =>
    execFileSync('npx', ['-y', MAPSHAPER, ...args], { stdio: 'inherit' })
  const precision = 'precision=0.0001'

  await writeFile(input, JSON.stringify({ features: regions, type: 'FeatureCollection' }))
  mapshaper(
    input,
    '-simplify',
    SIMPLIFY,
    'keep-shapes',
    '-clean',
    '-o',
    'format=geojson',
    precision,
    simplified,
  )
  // The world with China's outline cut out of it, from the same simplified lines, in
  // tiles: one ring round the world holed by one round China, Mapbox fills wrongly,
  // with wedges across the south.
  await writeFile(tiles, JSON.stringify(getWorldTiles()))
  mapshaper(
    simplified,
    'name=china',
    '-dissolve',
    '-i',
    tiles,
    'name=world',
    '-erase',
    'china',
    'target=world',
    '-o',
    'format=geojson',
    precision,
    outline,
  )

  const { features } = JSON.parse(await readFile(simplified, 'utf8'))
  // Written as bare geometries, having no properties left to keep: each piece of the
  // world a region of its own, so the fog sweeps across the world as across the cities.
  const erased = JSON.parse(await readFile(outline, 'utf8'))
  const outside = (erased.geometries ?? erased.features.map((feature) => feature.geometry)).map(
    (geometry) => ({
      geometry,
      properties: { fogged: true, id: 'outside', name: '' },
      type: 'Feature',
    }),
  )
  const centred = [...features, ...outside].map((feature) => ({
    ...feature,
    properties: { ...feature.properties, ...getCentre(feature.geometry) },
  }))

  await mkdir(join(root, 'apps/web/src/data/boundaries'), { recursive: true })
  await writeFile(output, `${JSON.stringify({ features: centred, type: 'FeatureCollection' })}\n`)
  console.log(`${features.length} cities written to ${output}`)
} finally {
  await rm(work, { force: true, recursive: true })
}

/**
 * The world in tiles of `TILE` degrees, up to `WORLD_LATITUDE` each way, each a quarter
 * the size each way round China (`NEAR`): a tile clears as one as the fog sweeps, and
 * near China, where the map is mostly looked at, big ones show as blocks.
 */
function getWorldTiles() {
  const features = []
  const square = (west, south, size) => {
    const east = west + size
    const north = Math.min(south + size, WORLD_LATITUDE)
    const ring = [
      [west, south],
      [east, south],
      [east, north],
      [west, north],
      [west, south],
    ]

    features.push({
      geometry: { coordinates: [ring], type: 'Polygon' },
      properties: {},
      type: 'Feature',
    })
  }

  for (let west = -180; west < 180; west += TILE) {
    for (let south = -WORLD_LATITUDE; south < WORLD_LATITUDE; south += TILE) {
      const near =
        west < NEAR.east &&
        west + TILE > NEAR.west &&
        south < NEAR.north &&
        south + TILE > NEAR.south

      if (!near) {
        square(west, south, TILE)
        continue
      }

      const size = TILE / 4

      for (let x = west; x < west + TILE; x += size) {
        for (let y = south; y < Math.min(south + TILE, WORLD_LATITUDE); y += size) {
          square(x, y, size)
        }
      }
    }
  }

  return { features, type: 'FeatureCollection' }
}

/**
 * The middle of the box round a region, `cx` and `cy`: where the fog's sweep reaches it,
 * as far from the middle of the screen as that is.
 */
function getCentre(geometry) {
  const polygons = geometry.type === 'Polygon' ? [geometry.coordinates] : geometry.coordinates
  const points = polygons.flatMap(([outer]) => outer)
  const longitudes = points.map(([longitude]) => longitude)
  const latitudes = points.map(([, latitude]) => latitude)
  const round = (value) => Math.round(value * 1000) / 1000

  return {
    cx: round((Math.min(...longitudes) + Math.max(...longitudes)) / 2),
    cy: round((Math.min(...latitudes) + Math.max(...latitudes)) / 2),
  }
}
