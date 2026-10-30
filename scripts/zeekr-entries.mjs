import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

import { entrySchema, getEntryFile, getStableUlid } from '@hyoban/data-spec/entry'
import { getVehicleCharges, readVehicleMonths } from '../apps/web/src/data/vehicle.ts'
import { dataRoot } from '../apps/web/src/data/store.ts'

// vp run zeekr:entries [--write]
//
// Each charging session of the car's months (data/zeekr) as an entry with a `charge` part,
// at when it started and ending when it stopped, its uid made from that time, so running it
// again after a new capture adds only the new sessions and leaves the rest as they are. It
// writes them under data/entries; `vp run data:push` then sends the new ones to the API.
// Without --write it only says what it would add.

const write = process.argv.includes('--write')
const charges = getVehicleCharges(readVehicleMonths())
const added = []

for (const charge of charges) {
  const at = charge.startedAt
  const entry = entrySchema.parse({
    uid: getStableUlid(at, `charge:${at}`),
    at,
    end: new Date(Date.parse(at) + charge.durationSeconds * 1000)
      .toISOString()
      .replace(/\.\d{3}Z$/, 'Z'),
    parts: {
      charge: {
        v: 1,
        type: charge.type,
        energyWh: charge.energyWh,
        durationMin: Math.round(charge.durationSeconds / 60),
      },
    },
    published: { at },
    createdAt: at,
    updatedAt: at,
  })
  const file = getEntryFile(entry)
  const existing = await readFile(join(dataRoot, file), 'utf8').catch(() => null)

  if (existing === null) {
    added.push(file)

    if (write) {
      await mkdir(dirname(join(dataRoot, file)), { recursive: true })
      await writeFile(join(dataRoot, file), `${JSON.stringify(entry, null, 2)}\n`)
    }
  }
}

console.log(`${charges.length} charging sessions, ${added.length} new${write ? ' written' : ''}.`)

for (const file of added) {
  console.log(`  data/${file}`)
}
