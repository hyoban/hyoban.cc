import assert from 'node:assert/strict'
import { cp, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'vite-plus/test'

import { writeSnapshot } from '../../src/data/snapshot.ts'
import { listJsonFiles, readJson } from '../../src/data/store.ts'

// The backup writes the API's export over data/: what is already there stays byte for
// byte, what the API no longer has goes, and what isn't the API's stays.

const snapshot = {
  revision: 1,
  manifest: readJson('manifest.json'),
  places: readJson('places.json'),
  trips: listJsonFiles('trips').map((file) => readJson(file)),
  moments: listJsonFiles('moments').map((file) => readJson(file)),
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
  const [gone, edited, ...rest] = snapshot.moments
  const changed = { ...edited, text: `${edited.text}!` }
  const devices = await readFile(join(root, 'devices.json'), 'utf8')
  const goneFile = listJsonFiles('moments', root).find((file) => file.endsWith(`${gone.uid}.json`))
  const editedFile = listJsonFiles('moments', root).find((file) =>
    file.endsWith(`${edited.uid}.json`),
  )

  await writeFile(join(root, 'stray.txt'), 'not a record')

  const written = await writeSnapshot(
    { ...snapshot, moments: [changed, ...rest], trips: snapshot.trips.slice(1) },
    root,
  )

  assert.deepEqual(
    written.sort((first, second) => first.localeCompare(second)),
    [editedFile, goneFile, `trips/${snapshot.trips[0].id}.json`].sort((first, second) =>
      first.localeCompare(second),
    ),
  )
  assert.deepEqual(readJson(editedFile, root), changed)
  assert.equal(await readFile(join(root, 'devices.json'), 'utf8'), devices)
  assert.equal(await readFile(join(root, 'stray.txt'), 'utf8'), 'not a record')
})
