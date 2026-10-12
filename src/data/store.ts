import { readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import {
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  tripRecordSchema,
  type Manifest,
  type MomentRecord,
  type Places,
  type TripRecord,
} from './spec.ts'

// Reads the repository's `data/` directory, the committed copy of the recording app's
// data, for the site's build and for the scripts. Everything is validated on the way in.

export const dataRoot = resolve(process.cwd(), 'data')

export function readManifest(): Manifest {
  return manifestSchema.parse(readJson('manifest.json'))
}

export function readPlaces(): Places {
  return placesSchema.parse(readJson('places.json'))
}

export function readTripRecords(): TripRecord[] {
  return listJsonFiles('trips').map((file) => tripRecordSchema.parse(readJson(file)))
}

/** Every Moment with the file it came from, relative to `data/`. */
export function readMomentRecords(): { file: string; record: MomentRecord }[] {
  return listJsonFiles('moments').map((file) => ({
    file,
    record: momentRecordSchema.parse(readJson(file)),
  }))
}

export function readJson(file: string): unknown {
  return JSON.parse(readFileSync(join(dataRoot, file), 'utf8'))
}

/** The JSON files under a directory of `data/`, as paths relative to `data/`, sorted. */
export function listJsonFiles(directory: string) {
  return readdirSync(join(dataRoot, directory), { recursive: true })
    .map(String)
    .filter((file) => file.endsWith('.json'))
    .sort()
    .map((file) => `${directory}/${file.split('\\').join('/')}`)
}
