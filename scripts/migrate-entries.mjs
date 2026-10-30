import { mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'

import {
  collectionSchema,
  collectionToTrip,
  entrySchema,
  entryToMoment,
  getCollectionFile,
  getEntryAudience,
  getEntryFile,
  isInCollection,
  isMarkEntry,
  isMomentEntry,
  momentToEntry,
  tripToCollection,
} from '@hyoban/data-spec/entry'
import { getMomentFile, momentRecordSchema, tripRecordSchema } from '@hyoban/data-spec'
import { getDataRoot, listJsonFiles, readJson } from '../apps/web/src/data/store.ts'

// vp run data:migrate [--write] [--rollback] [--force]
//
// Moves data/ from the first shape (Moments under moments/, trips under trips/) to entries
// and collections (entries/, collections/), as packages/data-spec/src/entry.ts maps them:
// a Moment's words, photos and meal become parts, hidden becomes private, a meal's bill its
// cost and people, a trip a collection with its old id as its slug and its waypoints marks
// of their own. Every record is checked to come back exactly before anything is written.
// Without --write it only says what it would do. --rollback goes the other way, refusing
// entries the first shape can't hold (a bill alone, a track) unless --force leaves them out.
// It runs again safely: what is already moved stays as it is. The API's database follows
// with `vp run data:import --remote` once data/ is as it should be.

const dataRoot = getDataRoot()
const options = new Set(process.argv.slice(2))
const unknown = [...options].filter(
  (option) => !['--write', '--rollback', '--force'].includes(option),
)

if (unknown.length > 0) {
  throw new Error(`Unknown option: ${unknown.join(' ')}`)
}

const write = options.has('--write')
const plan = options.has('--rollback') ? rollback() : forward()

console.log(plan.summary.join('\n'))

if (plan.samples.length > 0) {
  console.log('\nFor example:')

  for (const sample of plan.samples) {
    console.log(
      `\n${sample.from}\n${JSON.stringify(sample.before)}\n→ ${sample.to}\n${JSON.stringify(sample.after)}`,
    )
  }
}

if (!write) {
  console.log('\nNothing written; run again with --write to move data/.')
} else {
  for (const [file, record] of plan.writes) {
    await mkdir(dirname(join(dataRoot, file)), { recursive: true })
    await writeFile(join(dataRoot, file), `${JSON.stringify(record, null, 2)}\n`)
  }

  for (const file of plan.removes) {
    await rm(join(dataRoot, file))
  }

  // The directories left empty go too.
  for (const directory of new Set(plan.removes.map((file) => file.split('/')[0]))) {
    if (listJsonFiles(directory, dataRoot).length === 0) {
      await rm(join(dataRoot, directory), { recursive: true, force: true })
    }
  }

  const manifest = JSON.parse(await readFile(join(dataRoot, 'manifest.json'), 'utf8'))
  await writeFile(
    join(dataRoot, 'manifest.json'),
    `${JSON.stringify({ ...manifest, schemaVersion: plan.schemaVersion }, null, 2)}\n`,
  )
  console.log(`\nWrote ${plan.writes.size} files and removed ${plan.removes.length}.`)
}

function forward() {
  const momentFiles = listJsonFiles('moments', dataRoot)
  const tripFiles = listJsonFiles('trips', dataRoot)
  // Moved as written rather than as parsed, so each record keeps its keys' order both ways.
  const moments = momentFiles.map((file) => {
    const raw = readJson(file, dataRoot)
    return { file, raw, record: momentRecordSchema.parse(raw) }
  })
  const trips = tripFiles.map((file) => ({
    file,
    record: tripRecordSchema.parse(readJson(file, dataRoot)),
  }))
  const existing = new Set(listJsonFiles('entries', dataRoot))
  const writes = new Map()
  const samples = []
  const counts = { private: 0, hidden: 0, tech: 0, meals: 0, costs: 0, marks: 0, covers: 0 }

  for (const { file, raw, record } of moments) {
    const written = momentToEntry(raw)
    const entry = entrySchema.parse(written)
    const back = momentRecordSchema.parse(entryToMoment(entry, record.trip))
    assertSame(back, record, file)
    writes.set(getEntryFile(entry), written)
    counts.private += getEntryAudience(entry) === 'private' ? 1 : 0
    counts.hidden += record.hidden ? 1 : 0
    counts.tech += record.topic === 'tech' ? 1 : 0
    counts.meals += record.food ? 1 : 0
    counts.costs += record.food?.bill ? 1 : 0

    if (record.food?.bill && samples.length === 0) {
      samples.push({
        from: file,
        before: record.food,
        to: getEntryFile(entry),
        after: entry.parts.food,
      })
    }
  }

  const allMoments = moments.map(({ record }) => record)

  for (const { file, record } of trips) {
    const { collection, marks } = tripToCollection(record, allMoments)
    const parsed = collectionSchema.parse(collection)
    const entries = marks.map((mark) => entrySchema.parse(mark))
    assertSame(tripRecordSchema.parse(collectionToTrip(parsed, entries)), record, file)
    writes.set(getCollectionFile(parsed), parsed)
    counts.covers += parsed.covers.length

    for (const mark of entries) {
      writes.set(getEntryFile(mark), mark)
      counts.marks += 1
    }

    if (samples.length < 2 && record.waypoints.length > 0) {
      samples.push({
        from: file,
        before: { id: record.id, waypoints: record.waypoints },
        to: getCollectionFile(parsed),
        after: {
          uid: parsed.uid,
          slug: parsed.slug,
          marks: entries.map(({ uid, at, place }) => ({ uid, at, place })),
        },
      })
    }
  }

  const clashes = [...writes.keys()].filter((file) => existing.has(file))
  const uids = [...writes.values()].filter((record) => 'at' in record).map((record) => record.uid)

  if (new Set(uids).size !== uids.length) {
    throw new Error('Two entries would have the same uid.')
  }

  return {
    schemaVersion: 3,
    writes,
    removes: [...momentFiles, ...tripFiles],
    samples,
    summary: [
      `${moments.length} Moments → entries (${counts.private} private: ${counts.hidden} hidden, ${counts.tech} tech)`,
      `  ${counts.meals} with a meal, ${counts.costs} of them with a bill → food.cost and food.people`,
      `${trips.length} trips → collections, their ids kept as slugs, ${counts.covers} covers`,
      `  ${counts.marks} waypoints → mark entries`,
      `${existing.size} entries already under entries/${clashes.length > 0 ? `, ${clashes.length} of them written again` : ''}`,
      `Every record comes back the same from its new shape.`,
      `${momentFiles.length + tripFiles.length} files under moments/ and trips/ to remove, ${writes.size} to write.`,
    ],
  }
}

function rollback() {
  const entryFiles = listJsonFiles('entries', dataRoot)
  const collectionFiles = listJsonFiles('collections', dataRoot)
  const entries = entryFiles.map((file) => {
    const raw = readJson(file, dataRoot)
    return { file, raw, record: entrySchema.parse(raw) }
  })
  const collections = collectionFiles.map((file) => ({
    file,
    record: collectionSchema.parse(readJson(file, dataRoot)),
  }))
  const all = entries.map(({ record }) => record)
  const writes = new Map()
  const lost = []

  for (const { file, record } of collections) {
    const trip = tripRecordSchema.parse(collectionToTrip(record, all))
    writes.set(`trips/${trip.id}.json`, trip)

    if (record.visibility || record.facets) {
      lost.push(`${file}: its visibility and facets`)
    }
  }

  for (const { file, raw, record } of entries) {
    if (isMomentEntry(record)) {
      const trip = collections.find(
        ({ record: collection }) =>
          collection.include.includes(record.uid) && isInCollection(collection, record),
      )
      const moment = entryToMoment(raw, trip?.record.slug)
      momentRecordSchema.parse(moment)
      writes.set(getMomentFile(moment), moment)
      const kept = new Set(['text', 'media', 'food'])

      if (
        Object.keys(record.parts).some((plugin) => !kept.has(plugin)) ||
        record.point ||
        record.links
      ) {
        lost.push(`${file}: what isn't words, photos or a meal`)
      }
    } else if (
      !(
        isMarkEntry(record) &&
        collections.some(({ record: collection }) => isInCollection(collection, record))
      )
    ) {
      lost.push(`${file}: an entry the first shape has no place for`)
    }
  }

  if (lost.length > 0 && !options.has('--force')) {
    throw new Error(
      `Going back would lose:\n${lost.join('\n')}\nRun with --force to leave them out.`,
    )
  }

  return {
    schemaVersion: 2,
    writes,
    removes: [...entryFiles, ...collectionFiles],
    samples: [],
    summary: [
      `${entries.length} entries and ${collections.length} collections → ${writes.size} Moments and trips`,
      ...(lost.length > 0 ? [`Left out: ${lost.length}`, ...lost.map((line) => `  ${line}`)] : []),
    ],
  }
}

function assertSame(back, record, file) {
  if (JSON.stringify(sortKeys(back)) !== JSON.stringify(sortKeys(record))) {
    throw new Error(`${file} doesn't come back the same from its new shape.`)
  }
}

function sortKeys(value) {
  if (Array.isArray(value)) {
    return value.map(sortKeys)
  }

  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, sortKeys(value[key])]),
    )
  }

  return value
}
