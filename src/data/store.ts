import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import {
  devicesSchema,
  manifestSchema,
  momentRecordSchema,
  placesSchema,
  tripRecordSchema,
  type Devices,
  type Manifest,
  type MomentRecord,
  type Places,
  type TripRecord,
} from '@hyoban/data-spec'

// Reads the repository's `data/` directory, the committed copy of the recording app's
// data, for the site's build and for the scripts. Everything is validated on the way in.

export const dataRoot = resolve(process.cwd(), 'data')

export function readManifest(root = dataRoot): Manifest {
  return manifestSchema.parse(readJson('manifest.json', root))
}

export function readPlaces(root = dataRoot): Places {
  return placesSchema.parse(readJson('places.json', root))
}

export function readDevices(root = dataRoot): Devices {
  return devicesSchema.parse(readJson('devices.json', root))
}

export function readTripRecords(root = dataRoot): TripRecord[] {
  return listJsonFiles('trips', root).map((file) => tripRecordSchema.parse(readJson(file, root)))
}

/** Every Moment with the file it came from, relative to `data/`. */
export function readMomentRecords(root = dataRoot): { file: string; record: MomentRecord }[] {
  return listJsonFiles('moments', root).map((file) => ({
    file,
    record: momentRecordSchema.parse(readJson(file, root)),
  }))
}

export function readJson(file: string, root = dataRoot): unknown {
  return JSON.parse(readFileSync(join(root, file), 'utf8'))
}

/** The JSON files under a directory of `data/`, as paths relative to `data/`, sorted; none when it's missing. */
export function listJsonFiles(directory: string, root = dataRoot) {
  if (!existsSync(join(root, directory))) {
    return []
  }

  return readdirSync(join(root, directory), { recursive: true })
    .map(String)
    .filter((file) => file.endsWith('.json'))
    .sort()
    .map((file) => `${directory}/${file.split('\\').join('/')}`)
}
