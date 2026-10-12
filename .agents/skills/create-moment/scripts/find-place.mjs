// Find where the calendar map's Mapbox basemap puts a named place.
//
//   node .agents/skills/create-moment/scripts/find-place.mjs <latitude> <longitude> <name> [--gcj] [--radius <metres>]
//
// Pass the coordinates from any source as a starting point; add --gcj when they
// come from AMap or another Chinese map (GCJ-02), which sits a few hundred metres
// off the WGS-84 basemap. Prints the converted point and the nearby Mapbox POIs
// whose names share characters with <name>, best match first. Reads
// VITE_MAPBOX_TOKEN from the environment or the repository's .env.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
const flag = (name) => {
  const index = args.indexOf(name)
  if (index === -1) return undefined
  const [, value] = args.splice(index, 2)
  return value
}
const gcj = args.includes('--gcj')
if (gcj) args.splice(args.indexOf('--gcj'), 1)
const radius = Number(flag('--radius') ?? 1000)
const [latitudeArg, longitudeArg, ...nameParts] = args
const name = nameParts.join(' ')

if (!latitudeArg || !longitudeArg || !name) {
  console.error('Usage: find-place.mjs <latitude> <longitude> <name> [--gcj] [--radius <metres>]')
  process.exit(1)
}

const token = process.env.VITE_MAPBOX_TOKEN ?? readEnvToken()
if (!token) {
  console.error('Set VITE_MAPBOX_TOKEN or add it to .env.')
  process.exit(1)
}

let [latitude, longitude] = [Number(latitudeArg), Number(longitudeArg)]
if (gcj) [latitude, longitude] = gcjToWgs(latitude, longitude)
console.log(`point ${latitude.toFixed(5)}, ${longitude.toFixed(5)}${gcj ? ' (from GCJ-02)' : ''}`)

const url = new URL(
  `https://api.mapbox.com/v4/mapbox.mapbox-streets-v8/tilequery/${longitude},${latitude}.json`,
)
url.search = new URLSearchParams({
  radius: String(radius),
  limit: '50',
  layers: 'poi_label',
  dedupe: 'true',
  access_token: token,
}).toString()
const response = await fetch(url)
if (!response.ok) throw new Error(`Mapbox tilequery failed: ${response.status}`)
const { features } = await response.json()

const core = coreName(name)
const matches = features
  .map((feature) => {
    const label = feature.properties.name_zh_Hans ?? feature.properties.name ?? ''
    return {
      label,
      score: similarity(core, coreName(label)),
      distance: Math.round(feature.properties.tilequery.distance),
      latitude: feature.geometry.coordinates[1],
      longitude: feature.geometry.coordinates[0],
    }
  })
  .filter((match) => match.score > 0)
  .sort((a, b) => b.score - a.score || a.distance - b.distance)
  .slice(0, 8)

if (matches.length === 0) console.log('no Mapbox POI with a similar name nearby')
for (const match of matches) {
  console.log(
    `${match.score.toFixed(2)}  ${match.latitude.toFixed(5)}, ${match.longitude.toFixed(5)}  ${match.distance}m  ${match.label}`,
  )
}

function readEnvToken() {
  try {
    const env = readFileSync(fileURLToPath(new URL('../../../../.env', import.meta.url)), 'utf8')
    return env.match(/^VITE_MAPBOX_TOKEN=(.+)$/m)?.[1]?.trim()
  } catch {
    return undefined
  }
}

// Drop branch names in brackets and generic words so 店名（某某店） matches 店名.
function coreName(value) {
  return value
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/风景名胜区|风景区|景区|国家|森林|公园|古镇|有轨电车站|·/g, '')
    .trim()
}

// Share of the query's characters that appear in the label, in order.
function similarity(query, label) {
  if (!query || !label) return 0
  if (label.includes(query) || query.includes(label)) return 1
  let found = 0
  let from = 0
  for (const character of query) {
    const index = label.indexOf(character, from)
    if (index !== -1) {
      found += 1
      from = index + 1
    }
  }
  const score = found / query.length
  return score >= 0.5 ? score : 0
}

function gcjToWgs(lat, lng) {
  let wgsLat = lat
  let wgsLng = lng
  for (let i = 0; i < 10; i += 1) {
    const [dLat, dLng] = gcjDelta(wgsLat, wgsLng)
    wgsLat = lat - dLat
    wgsLng = lng - dLng
  }
  return [wgsLat, wgsLng]
}

function gcjDelta(lat, lng) {
  const a = 6378245
  const ee = 0.006693421622965943
  const x = lng - 105
  const y = lat - 35
  let dLat =
    -100 +
    2 * x +
    3 * y +
    0.2 * y * y +
    0.1 * x * y +
    0.2 * Math.sqrt(Math.abs(x)) +
    ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3 +
    ((20 * Math.sin(y * Math.PI) + 40 * Math.sin((y / 3) * Math.PI)) * 2) / 3 +
    ((160 * Math.sin((y / 12) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30)) * 2) / 3
  let dLng =
    300 +
    x +
    2 * y +
    0.1 * x * x +
    0.1 * x * y +
    0.1 * Math.sqrt(Math.abs(x)) +
    ((20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2) / 3 +
    ((20 * Math.sin(x * Math.PI) + 40 * Math.sin((x / 3) * Math.PI)) * 2) / 3 +
    ((150 * Math.sin((x / 12) * Math.PI) + 300 * Math.sin((x / 30) * Math.PI)) * 2) / 3
  const radians = (lat / 180) * Math.PI
  const magic = 1 - ee * Math.sin(radians) ** 2
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180) / (((a * (1 - ee)) / (magic * sqrtMagic)) * Math.PI)
  dLng = (dLng * 180) / ((a / sqrtMagic) * Math.cos(radians) * Math.PI)
  return [dLat, dLng]
}
