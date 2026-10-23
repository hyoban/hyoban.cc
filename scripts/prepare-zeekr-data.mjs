import { readFile, mkdir, writeFile, rename } from 'node:fs/promises'
import { basename, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { canonicalJson, hashBytes } from './lib/zeekr-data.mjs'
import { redactZeekrCapture } from './lib/zeekr-privacy.mjs'

const [input, deviceId] = process.argv.slice(2)
if (!input || !deviceId || process.argv.length !== 4)
  throw new Error('Usage: prepare-zeekr-data.mjs <private-capture.json> <device-id>')
const root = fileURLToPath(new URL('../data/zeekr/', import.meta.url))
const devices = JSON.parse(await readFile(new URL('../data/devices.json', import.meta.url), 'utf8'))
if (!devices[deviceId]) throw new Error('Unknown device id')
const raw = JSON.parse(await readFile(input, 'utf8'))
const text = canonicalJson(redactZeekrCapture(raw))
const file = `sources/${basename(input)}`
await mkdir(join(root, 'sources'), { recursive: true })
await writeFile(join(root, file) + '.tmp', text)
await rename(join(root, file) + '.tmp', join(root, file))
const registry = join(root, 'imports.json')
const imports = JSON.parse(await readFile(registry, 'utf8'))
const record = {
  file,
  deviceId,
  timezone: 'Asia/Shanghai',
  offset: '+08:00',
  sha256: hashBytes(text),
}
const next = imports.filter((entry) => entry.file !== file)
next.push(record)
await writeFile(registry + '.tmp', canonicalJson(next))
await rename(registry + '.tmp', registry)
console.log(`Prepared statistical source: ${file}`)
