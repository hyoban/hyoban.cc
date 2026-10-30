import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, readdir, rename, rm, stat, utimes, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

// Assets keep their cache when the card's layout changes. Deduplicate concurrent
// requests too: a photo can appear in a month, a day, a Moment and a trip.
const directory = join(process.cwd(), 'node_modules', '.astro', 'share-assets')
let ready: Promise<void> | undefined
const pending = new Map<string, Promise<Buffer>>()

export function cachedOgAsset(name: string, inputs: unknown, render: () => Promise<Buffer>) {
  const key = createHash('sha256')
    .update(JSON.stringify([name, inputs]))
    .digest('hex')
  const existing = pending.get(key)
  if (existing) return existing
  const result = (async () => {
    ready ??= (async () => {
      await mkdir(directory, { recursive: true })
      for (const name of await readdir(directory)) {
        const file = join(directory, name)
        const info = await stat(file).catch(() => undefined)
        if (info && Date.now() - info.mtimeMs > 30 * 86400000) await rm(file, { force: true })
      }
    })()
    await ready
    const file = join(directory, `${key}-${name}`)
    try {
      const data = await readFile(file)
      const now = new Date()
      await utimes(file, now, now)
      return data
    } catch {}
    const data = await render()
    // A second build must never see a partially written image or font.
    const temporary = `${file}.${randomUUID()}.tmp`
    await writeFile(temporary, data)
    await rename(temporary, file)
    return data
  })()
  pending.set(key, result)
  void result.finally(() => pending.delete(key)).catch(() => {})
  return result
}
