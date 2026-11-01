import assert from 'node:assert/strict'
import { cp, mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'vite-plus/test'

import { writeSnapshot } from '../../apps/web/src/data/snapshot.ts'
import { listJsonFiles, readJson } from '../../apps/web/src/data/store.ts'

// `pnpm data:export` writes the API's export over data/: what is already there stays byte
// for byte, what the API no longer has goes, and what isn't the API's stays. Over a copy of
// the made-up fixture, with a ledger of the car's beside it.

const fixtureRoot = fileURLToPath(
  new URL('../../packages/data-spec/test/fixtures/data/', import.meta.url),
)
const snapshot = {
  revision: 1,
  manifest: readJson('manifest.json', fixtureRoot),
  places: readJson('places.json', fixtureRoot),
  entries: listJsonFiles('entries', fixtureRoot).map((file) => readJson(file, fixtureRoot)),
  collections: listJsonFiles('collections', fixtureRoot).map((file) => readJson(file, fixtureRoot)),
  tracks: listJsonFiles('tracks', fixtureRoot).map((file) => readJson(file, fixtureRoot)),
  routes: readJson('routes.json', fixtureRoot),
}

async function copyOfData() {
  const root = await mkdtemp(join(tmpdir(), 'hyoban-snapshot-test-'))

  await cp(fixtureRoot, root, { recursive: true })
  await mkdir(join(root, 'zeekr/expenses'), { recursive: true })
  await writeFile(join(root, 'zeekr/expenses/zeekr-007gt.json'), '{ "expenses": [] }\n')

  return root
}

test('writing the export back over itself changes nothing', async () => {
  assert.deepEqual(await writeSnapshot(snapshot, await copyOfData()), [])
})

test("what the API no longer has is removed, and the car's expenses are left alone", async () => {
  const root = await copyOfData()
  const moments = snapshot.entries.filter((entry) => entry.parts.text)
  const [gone, edited] = moments
  const changed = {
    ...edited,
    parts: { ...edited.parts, text: { ...edited.parts.text, text: `${edited.parts.text.text}!` } },
  }
  const expenses = await readFile(join(root, 'zeekr/expenses/zeekr-007gt.json'), 'utf8')
  const fileOf = (uid) =>
    listJsonFiles('entries', root).find((file) => file.endsWith(`${uid}.json`))
  const goneFile = fileOf(gone.uid)
  const editedFile = fileOf(edited.uid)
  const [firstCollection, ...collections] = snapshot.collections

  await writeFile(join(root, 'stray.txt'), 'not a record')

  const written = await writeSnapshot(
    {
      ...snapshot,
      entries: snapshot.entries
        .filter((entry) => entry.uid !== gone.uid)
        .map((entry) => (entry.uid === edited.uid ? changed : entry)),
      collections,
    },
    root,
  )

  assert.deepEqual(
    written.sort((first, second) => first.localeCompare(second)),
    [editedFile, goneFile, `collections/${firstCollection.slug}.json`].sort((first, second) =>
      first.localeCompare(second),
    ),
  )
  assert.deepEqual(readJson(editedFile, root), changed)
  assert.equal(await readFile(join(root, 'zeekr/expenses/zeekr-007gt.json'), 'utf8'), expenses)
  assert.equal(await readFile(join(root, 'stray.txt'), 'utf8'), 'not a record')
})
