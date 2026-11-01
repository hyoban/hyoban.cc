import {
  collectionSchema,
  entrySchema,
  getDeviceIssues,
  getUnknownFields,
} from '@hyoban/data-spec/entry'
import { getDataRoot, listJsonFiles, readJson, readPlaces } from '../apps/web/src/data/store.ts'

// vp run data:check
//
// Checks data/ (or HYOBAN_DATA_ROOT) as a whole, past what each record's schema sees: every
// entry and collection parses, a device id names one device and a charge a device there is,
// what an entry or collection names is there, and no field is one the spec doesn't name.
// The schemas are loose so a newer app's fields travel through, which also lets a typo
// through; those are warned about here rather than turned away. Fails on the rest.

const root = getDataRoot()
const errors = []
const warnings = []
const entries = []

for (const file of listJsonFiles('entries', root)) {
  const raw = readJson(file, root)
  const parsed = entrySchema.safeParse(raw)

  if (!parsed.success) {
    errors.push(`${file}: ${parsed.error.issues.map((issue) => issue.message).join('; ')}`)
    continue
  }

  entries.push(parsed.data)

  for (const field of getUnknownFields(raw)) {
    warnings.push(`${file}: ${field} isn't in the spec.`)
  }
}

const uids = new Set(entries.map((entry) => entry.uid))
const places = readPlaces(root)

for (const issue of getDeviceIssues(entries)) {
  errors.push(`${issue.uid}: ${issue.message}`)
}

for (const entry of entries) {
  if (entry.place && !places[entry.place]) {
    errors.push(`${entry.uid}: the place ${entry.place} isn't in places.json.`)
  }
}

for (const file of listJsonFiles('collections', root)) {
  const parsed = collectionSchema.safeParse(readJson(file, root))

  if (!parsed.success) {
    errors.push(`${file}: ${parsed.error.issues.map((issue) => issue.message).join('; ')}`)
    continue
  }

  const collection = parsed.data
  const named = [
    ...collection.include,
    ...collection.exclude,
    ...collection.covers.map((cover) => cover.entry),
  ]

  for (const uid of named.filter((uid) => !uids.has(uid))) {
    errors.push(`${file}: names ${uid}, which isn't an entry.`)
  }

  for (const leg of [
    ...(collection.route?.straightLegs ?? []),
    ...(collection.route?.sameWayBack ?? []),
  ]) {
    for (const place of [leg.from, leg.to].filter((place) => !places[place])) {
      errors.push(`${file}: its route names ${place}, which isn't in places.json.`)
    }
  }
}

for (const warning of warnings) {
  console.warn(`warning: ${warning}`)
}

for (const error of errors) {
  console.error(`error: ${error}`)
}

console.log(
  `${entries.length} entries checked: ${errors.length} error(s), ${warnings.length} warning(s).`,
)

process.exitCode = errors.length > 0 ? 1 : 0
