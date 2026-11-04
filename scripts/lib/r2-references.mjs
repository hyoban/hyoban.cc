import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'

/**
 * Every R2 key under `prefix` that anything in `data/` names: each string in every JSON file,
 * whatever record, part or field holds it, public or private, as a bare key or inside an
 * image URL. The garbage collector keeps all of them, so nothing a record still names, the
 * site showing it or not, looks unreferenced.
 */
export async function readReferencedR2Keys(dataRoot, prefix = 'moments/') {
  const pattern = new RegExp(`(?:^|/)(${escapeRegExp(prefix)}[^\\s?#"')]+)`, 'g')
  const keys = new Set()
  const files = (await readdir(dataRoot, { recursive: true }))
    .map(String)
    .filter((file) => file.endsWith('.json'))

  for (const file of files) {
    visit(JSON.parse(await readFile(join(dataRoot, file), 'utf8')))
  }

  return keys

  function visit(value) {
    if (typeof value === 'string') {
      for (const match of value.matchAll(pattern)) {
        keys.add(match[1])
      }
    } else if (Array.isArray(value)) {
      value.forEach(visit)
    } else if (value && typeof value === 'object') {
      Object.values(value).forEach(visit)
    }
  }
}

function escapeRegExp(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
