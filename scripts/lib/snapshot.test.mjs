import assert from 'node:assert/strict'
import { cp, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'vite-plus/test'

import { writeSnapshot } from '../../apps/web/src/data/snapshot.ts'
import { listJsonFiles, readJson } from '../../apps/web/src/data/store.ts'

// The backup writes the API's export over data/: what is already there stays byte for
// byte, what the API no longer has goes, and what isn't the API's stays.

const snapshot = {
  revision: 1,
  manifest: readJson('manifest.json'),
  places: readJson('places.json'),
  entries: listJsonFiles('entries').map((file) => readJson(file)),
  collections: listJsonFiles('collections').map((file) => readJson(file)),
  tracks: listJsonFiles('tracks').map((file) => readJson(file)),
  routes: readJson('routes.json'),
}

async function copyOfData() {
  const root = await mkdtemp(join(tmpdir(), 'hyoban-snapshot-test-'))

  await cp('data', root, { recursive: true })

  return root
}

test('writing data/ back over itself changes nothing', async () => {
  assert.deepEqual(await writeSnapshot(snapshot, await copyOfData()), [])
})

test('what the API no longer has is removed, and devices.json is left alone', async () => {
  const root = await copyOfData()
  const moments = snapshot.entries.filter((entry) => entry.parts.text)
  const [gone, edited] = moments
  const changed = {
    ...edited,
    parts: { ...edited.parts, text: { ...edited.parts.text, text: `${edited.parts.text.text}!` } },
  }
  const devices = await readFile(join(root, 'devices.json'), 'utf8')
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
  assert.equal(await readFile(join(root, 'devices.json'), 'utf8'), devices)
  assert.equal(await readFile(join(root, 'stray.txt'), 'utf8'), 'not a record')
})
