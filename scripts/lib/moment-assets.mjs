import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'

import { parseMomentDocument } from '../../src/moments/content.ts'

export const momentIdPattern = /^(\d{4})\/(\d{2})\/(\d{2})-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*$/

export async function readMomentRecords(contentRoot) {
  const files = (await readdir(contentRoot, { recursive: true }))
    .filter(file => file.endsWith('index.md'))
    .sort()

  return Promise.all(files.map(async (file) => {
    const id = file.replace(/\/index\.md$/, '')
    const directory = join(contentRoot, id)
    const document = await readFile(join(contentRoot, file), 'utf8')
    const moment = parseMomentDocument(document, { id })
    const assets = JSON.parse(await readFile(join(directory, 'assets.json'), 'utf8'))

    return {
      assets,
      directory,
      document,
      file,
      id,
      moment,
    }
  }))
}

export function getReferencedAssetFiles(record) {
  const referenced = new Set()

  for (const media of record.moment.media) {
    addAsset(media.file)

    if (media.poster) {
      addAsset(media.poster)
    }
  }

  return referenced

  function addAsset(file) {
    if (referenced.has(file)) {
      return
    }

    referenced.add(file)

    for (const variant of record.assets[file]?.variants ?? []) {
      addAsset(variant)
    }
  }
}

export function getReferencedR2Objects(records) {
  return records.flatMap(record =>
    [...getReferencedAssetFiles(record)].map(file => ({
      file,
      key: `moments/${record.id}/${file}`,
      metadata: record.assets[file],
      record,
    })),
  )
}
