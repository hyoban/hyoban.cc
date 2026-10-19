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

// Reads the recording app's records for the site's build and for the scripts, from the
// repository's `data/` directory or, when the build fetched one, the API's snapshot in
// the same layout (see api-data.ts). Everything is validated on the way in.

/** The repository's `data/`, the committed copy. */
export const dataRoot = resolve(process.cwd(), 'data')

/** Set by the build to the directory it wrote the API's snapshot to. */
export const DATA_ROOT_VARIABLE = 'HYOBAN_DATA_ROOT'

/** Where the Moments, places and trips are read from: the API's snapshot, else `data/`. */
export function getDataRoot() {
  return process.env[DATA_ROOT_VARIABLE] ?? dataRoot
}

export function readManifest(root = getDataRoot()): Manifest {
  return manifestSchema.parse(readJson('manifest.json', root))
}

export function readPlaces(root = getDataRoot()): Places {
  return placesSchema.parse(readJson('places.json', root))
}

export function readDevices(root = dataRoot): Devices {
  return devicesSchema.parse(readJson('devices.json', root))
}

export function readTripRecords(root = getDataRoot()): TripRecord[] {
  return listJsonFiles('trips', root).map((file) => tripRecordSchema.parse(readJson(file, root)))
}

/** Every Moment with the file it came from, relative to `data/`. */
export function readMomentRecords(root = getDataRoot()): { file: string; record: MomentRecord }[] {
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
