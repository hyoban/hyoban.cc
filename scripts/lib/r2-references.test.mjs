import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'vite-plus/test'

import { readReferencedR2Keys } from './r2-references.mjs'

test('keeps every key data/ names, whatever holds it', async () => {
  const root = await mkdtemp(join(tmpdir(), 'r2-references-'))

  try {
    await mkdir(join(root, 'entries/2026/01'), { recursive: true })
    await mkdir(join(root, 'collections'), { recursive: true })
    // A Moment with words, a variant and a video's poster.
    await writeJson(root, 'entries/2026/01/a.json', {
      uid: '01AAAAAAAAAAAAAAAAAAAAAAAA',
      parts: {
        text: { text: 'Lunch' },
        media: {
          items: [
            {
              uid: '01BBBBBBBBBBBBBBBBBBBBBBBB',
              published: {
                key: 'moments/01aaaaaaaaaaaaaaaaaaaaaaaa/01bbbbbbbbbbbbbbbbbbbbbbbb.jpg',
                variants: [
                  {
                    key: 'moments/01aaaaaaaaaaaaaaaaaaaaaaaa/01bbbbbbbbbbbbbbbbbbbbbbbb-960w.webp',
                  },
                ],
                poster: {
                  key: 'moments/01aaaaaaaaaaaaaaaaaaaaaaaa/01bbbbbbbbbbbbbbbbbbbbbbbb-poster.webp',
                },
              },
            },
          ],
        },
      },
    })
    // No words, a private media part, no published block, and a key under another entry's directory.
    await writeJson(root, 'entries/2026/01/b.json', {
      uid: '01CCCCCCCCCCCCCCCCCCCCCCCC',
      visibility: 'private',
      parts: {
        media: {
          visibility: 'private',
          items: [
            {
              published: {
                key: 'moments/01dddddddddddddddddddddddd/01eeeeeeeeeeeeeeeeeeeeeeee.mp4',
              },
            },
          ],
        },
      },
    })
    // A cover named by its URL, resized through the image origin.
    await writeJson(root, 'collections/trip.json', {
      cover:
        'https://image.hyoban.cc/cdn-cgi/image/width=960,format=auto/moments/01ffffffffffffffffffffffff/cover.jpg?v=1',
      note: 'posts/an-article/diagram.webp',
    })

    const keys = [...(await readReferencedR2Keys(root, 'moments/'))]

    assert.deepEqual(
      keys.toSorted((first, second) => first.localeCompare(second)),
      [
        'moments/01aaaaaaaaaaaaaaaaaaaaaaaa/01bbbbbbbbbbbbbbbbbbbbbbbb-960w.webp',
        'moments/01aaaaaaaaaaaaaaaaaaaaaaaa/01bbbbbbbbbbbbbbbbbbbbbbbb-poster.webp',
        'moments/01aaaaaaaaaaaaaaaaaaaaaaaa/01bbbbbbbbbbbbbbbbbbbbbbbb.jpg',
        'moments/01dddddddddddddddddddddddd/01eeeeeeeeeeeeeeeeeeeeeeee.mp4',
        'moments/01ffffffffffffffffffffffff/cover.jpg',
      ],
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

function writeJson(root, file, value) {
  return writeFile(join(root, file), JSON.stringify(value))
}
