import { writeFile } from 'node:fs/promises'
import { basename, join } from 'node:path'

import { dataRoot, readMomentRecords as readRecords } from '../../src/data/store.ts'

export const momentIdPattern = /^(\d{4})\/(\d{2})\/(\d{2})-(\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*$/

/**
 * Every published Moment under data/moments, with its media's R2 metadata keyed by file
 * name, the way the audit, the garbage collector and the variants generator read them.
 */
export async function readMomentRecords() {
  return readRecords().flatMap(({ file, record }) => {
    if (!record.published) {
      return []
    }

    const assets = {}
    const media = record.media.map((item) => {
      const { published } = item

      if (!published) {
        throw new Error(`Published Moment ${record.uid} has unpublished media ${item.uid}.`)
      }

      addAsset(published, item.type === 'video' ? undefined : published.variants)

      if (item.type === 'live-photo') {
        addAsset(published.video)
      }

      if (item.type === 'video' && published.poster) {
        addAsset(published.poster, published.poster.variants)
      }

      return {
        alt: item.alt,
        file: basename(published.key),
        type: item.type,
        uid: item.uid,
        ...(item.type === 'live-photo' ? { video: basename(published.video.key) } : {}),
        ...(item.type === 'video' && published.poster
          ? { poster: basename(published.poster.key) }
          : {}),
      }
    })

    return [
      {
        assets,
        file,
        id: record.published.id,
        moment: {
          hidden: record.hidden,
          location: record.places.length === 0 ? undefined : record.places,
          media,
          occurredAt: record.occurredAt,
          text: record.text,
        },
        path: join(dataRoot, file),
        record,
        uid: record.uid,
      },
    ]

    function addAsset(object, variants) {
      const { key, ...metadata } = object
      const file = basename(key)

      assets[file] = {
        bytes: metadata.bytes,
        contentType: metadata.contentType,
        etag: metadata.etag,
        ...(metadata.width === undefined ? {} : { width: metadata.width, height: metadata.height }),
        ...(variants === undefined
          ? {}
          : { variants: variants.map((variant) => basename(variant.key)) }),
      }

      for (const variant of variants ?? []) {
        addAsset(variant)
      }
    }
  })
}

/** Writes a Moment record back where it was read from, as the app would. */
export async function writeMomentRecord(record, path) {
  await writeFile(path, `${JSON.stringify(record, null, 2)}\n`)
}

export function getReferencedAssetFiles(record) {
  const referenced = new Set()

  for (const media of record.moment.media) {
    addAsset(media.file)

    if (media.type === 'live-photo') {
      addAsset(media.video)
    }

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
  return records.flatMap((record) =>
    [...getReferencedAssetFiles(record)].map((file) => ({
      file,
      key: `moments/${record.id}/${file}`,
      metadata: record.assets[file],
      record,
    })),
  )
}
