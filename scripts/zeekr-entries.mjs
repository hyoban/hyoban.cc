import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

import { getRecordTime } from '@hyoban/data-spec'
import { entrySchema, getEntryFile, getStableUlid } from '@hyoban/data-spec/entry'
import { VEHICLE_ID, getVehicleCharges, readVehicleMonths } from '../apps/web/src/data/vehicle.ts'
import { dataRoot } from '../apps/web/src/data/store.ts'

// vp run zeekr:entries [--write]
//
// Each charging session of the car's months (data/zeekr) as an entry with a `charge` part,
// at when it started and ending when it stopped, naming the car and where in the capture it
// came from, its uid made from that time. Running it again after a new capture adds the new
// sessions and brings the rest up to date, as only this import changes a charge it made; one
// whose figures did not change keeps its source. The car's app sometimes lists one session
// twice, the same start, energy and time: the months keep both rows as captured, and this
// makes one entry of them. It writes them under data/entries; `vp run data:push` then sends
// them to the API. Without --write it only says what it would write.

const write = process.argv.includes('--write')
const charges = getVehicleCharges(readVehicleMonths())
const now = getRecordTime()
const added = []
const updated = []
const made = new Map()
const repeated = []

for (const charge of charges) {
  const at = charge.startedAt
  const uid = getStableUlid(at, `charge:${at}`)
  const figures = JSON.stringify([charge.type, charge.energyWh, charge.durationSeconds])

  if (made.has(uid)) {
    if (made.get(uid) !== figures) {
      throw new Error(`Two charges start at ${at} with different figures; check the capture.`)
    }

    repeated.push(at)
    continue
  }

  made.set(uid, figures)

  const entry = entrySchema.parse({
    uid,
    at,
    end: getRecordTime(new Date(Date.parse(at) + charge.durationSeconds * 1000)),
    parts: {
      charge: {
        v: 1,
        type: charge.type,
        energyWh: charge.energyWh,
        durationSeconds: charge.durationSeconds,
        // For the apps installed before `durationSeconds`.
        durationMin: Math.round(charge.durationSeconds / 60),
        vehicle: VEHICLE_ID,
        source: charge.source,
      },
    },
    published: { at },
    createdAt: getRecordTime(new Date(at)),
    updatedAt: getRecordTime(new Date(at)),
  })
  const file = getEntryFile(entry)
  const existing = await readFile(join(dataRoot, file), 'utf8').catch(() => null)
  let next = entry

  if (existing !== null) {
    // Kept as published, with the charge as this import now has it; a charge whose figures
    // are the same keeps the capture it was first read from, so a new capture rewrites none.
    // Its figures are the ones both have, so a field the import has newly learnt to write
    // is added without moving the source.
    const prior = entrySchema.parse(JSON.parse(existing))
    const { source: priorSource, ...priorCharge } = prior.parts.charge ?? {}
    const { source: _source, ...charge } = entry.parts.charge
    const same =
      priorSource &&
      Object.entries(priorCharge).every(
        ([key, value]) => JSON.stringify(value) === JSON.stringify(charge[key]),
      )
    const merged = {
      ...prior,
      end: entry.end,
      parts: {
        ...prior.parts,
        charge: same ? { ...entry.parts.charge, source: priorSource } : entry.parts.charge,
      },
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

if (repeated.length > 0) {
  console.log(
    `${repeated.length} listed twice by the car's app, made one entry each: ${repeated.join(', ')}`,
  )
}

for (const file of [...added, ...updated]) {
  console.log(`  data/${file}`)
}
