import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { createDataVChildren, locateDivision } from '@hyoban/data-spec/division'

// pnpm places:locate [--all] [<id>...]
//
// Fills in each place's division, the province, city and district its point falls in,
// from Aliyun DataV's GeoAtlas (the boundaries the map's fog of war is drawn from): the
// places without one, the ids given, or every place with --all. The API does the same
// for each new place it is sent; this is for data/ written by hand, or a kind changed.
// Then push the places with pnpm data:push --place <id>.

const root = fileURLToPath(new URL('../', import.meta.url))
const placesFile = join(root, 'data/places.json')

const argv = process.argv.slice(2)
const all = argv.includes('--all')
const ids = argv.filter((argument) => !argument.startsWith('--'))
const places = JSON.parse(await readFile(placesFile, 'utf8'))
const unknown = ids.filter((id) => !places[id])

if (unknown.length > 0) {
  throw new Error(`data/places.json has no ${unknown.join(', ')}.`)
}

const targets = Object.keys(places).filter((id) =>
  ids.length > 0 ? ids.includes(id) : all || !places[id].division,
)
const childrenOf = createDataVChildren()

for (const id of targets) {
  const place = places[id]
  const division = await locateDivision(place, childrenOf)
  const { division: _, ...rest } = place
  // Keep the spec's order: the division right after the kind, or after the point.
  const entries = Object.entries(rest)
  const at = entries.findIndex(([key]) => key === (rest.kind ? 'kind' : 'longitude')) + 1

  if (division) {
    entries.splice(at, 0, ['division', division])
  }

  places[id] = Object.fromEntries(entries)
  console.log(
    `${id}: ${division ? [division.province, division.city, division.district].filter(Boolean).join(' ') : 'no division'}`,
  )
}

await writeFile(placesFile, `${JSON.stringify(places, null, 2)}\n`)
console.log(`Located ${targets.length} place(s).`)
