import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

import { entrySchema, getEntryFile, getStableUlid } from '@hyoban/data-spec/entry'
import { VEHICLE_ID, getVehicleCharges, readVehicleMonths } from '../apps/web/src/data/vehicle.ts'
import { dataRoot } from '../apps/web/src/data/store.ts'

// vp run zeekr:entries [--write]
//
// Each charging session of the car's months (data/zeekr) as an entry with a `charge` part,
// at when it started and ending when it stopped, naming the car and where in the capture it
// came from, its uid made from that time. Running it again after a new capture adds the new
// sessions and brings the rest up to date, as only this import changes a charge it made. It
// writes them under data/entries; `vp run data:push` then sends them to the API. Without
// --write it only says what it would write.

const write = process.argv.includes('--write')
const charges = getVehicleCharges(readVehicleMonths())
const now = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')
const added = []
const updated = []

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
        vehicle: VEHICLE_ID,
        source: charge.source,
      },
    },
    published: { at },
    createdAt: at,
    updatedAt: at,
  })
  const file = getEntryFile(entry)
  const existing = await readFile(join(dataRoot, file), 'utf8').catch(() => null)
  let next = entry

  if (existing !== null) {
    // Kept as published, with the charge as this import now has it.
    const prior = entrySchema.parse(JSON.parse(existing))
    const merged = {
      ...prior,
      end: entry.end,
      parts: { ...prior.parts, charge: entry.parts.charge },
    }

    next = JSON.stringify(merged) === JSON.stringify(prior) ? prior : { ...merged, updatedAt: now }
  }

  const text = `${JSON.stringify(next, null, 2)}\n`

  if (existing !== text) {
    ;(existing === null ? added : updated).push(file)

    if (write) {
      await mkdir(dirname(join(dataRoot, file)), { recursive: true })
      await writeFile(join(dataRoot, file), text)
    }
  }
}

console.log(
  `${charges.length} charging sessions, ${added.length} new and ${updated.length} brought up to date${write ? ', written' : ''}.`,
)

for (const file of [...added, ...updated]) {
  console.log(`  data/${file}`)
}
