import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

import { entrySchema, foodToCost } from '@hyoban/data-spec/entry'
import { getDataRoot, listJsonFiles, readJson } from '../apps/web/src/data/store.ts'

// vp run data:costs [--write]
//
// Moves each meal kept as a `food` part into the `cost` part it now is, of the kind `food`, in
// data/entries/: its dishes as what it went on, its verdict, what it cost and how many it fed,
// private when the meal was (`foodToCost`). Without --write it only says what it would do. It
// runs again safely: a moved entry has no `food` part left. An entry that has both is left as it
// is and named. Then push the files it names with `vp run data:push`.

const dataRoot = getDataRoot()
const options = new Set(process.argv.slice(2))
const unknown = [...options].filter((option) => option !== '--write')

if (unknown.length > 0) {
  throw new Error(`Unknown option: ${unknown.join(' ')}`)
}

const write = options.has('--write')
const moved = []
const left = []

for (const file of listJsonFiles('entries', dataRoot)) {
  const entry = readJson(file, dataRoot)

  if (!entry.parts?.food) {
    continue
  }

  if (entry.parts.cost) {
    left.push(file)
    continue
  }

  // Checked against the spec before and after, so nothing it can't read is written.
  const { food } = entrySchema.parse(entry).parts
  const { food: _food, ...rest } = entry.parts
  const after = { ...entry, parts: { ...rest, cost: foodToCost(food) } }
  entrySchema.parse(after)
  moved.push(file)

  if (write) {
    await writeFile(join(dataRoot, file), `${JSON.stringify(after, null, 2)}\n`)
  }
}

console.log(
  `${moved.length} meal(s) ${write ? 'moved' : 'to move'} to a cost of the kind food${moved.length ? ':' : '.'}`,
)

for (const file of moved) {
  console.log(`  ${join(dataRoot, file)}`)
}

if (left.length > 0) {
  console.log(`\n${left.length} entr(ies) with both a meal and a cost, left as they are:`)

  for (const file of left) {
    console.log(`  ${join(dataRoot, file)}`)
  }
}

if (!write && moved.length > 0) {
  console.log('\nNothing written; run again with --write.')
}
