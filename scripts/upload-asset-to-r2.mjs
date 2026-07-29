import { readFile } from 'node:fs/promises'
import { isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { uploadR2Object } from './lib/r2-assets.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const options = parseArguments(process.argv.slice(2))
const config = JSON.parse(
  await readFile(join(root, 'src/data/asset-config.json'), 'utf8'),
)
const file = isAbsolute(options.file) ? options.file : resolve(options.file)
const result = await uploadR2Object({
  bucket: config.bucket,
  file,
  key: options.key,
  origin: config.origin,
  root,
})

console.log(`${result.action}: ${result.url}`)

function parseArguments(argv) {
  const values = {}

  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index]
    const value = argv[index + 1]

    if ((flag !== '--file' && flag !== '--key') || !value) {
      throw new Error('Usage: upload-asset-to-r2.mjs --key <object-key> --file <path>')
    }

    values[flag.slice(2)] = value
  }

  if (!values.file || !values.key || values.key.startsWith('/') || values.key.includes('..')) {
    throw new Error('Usage: upload-asset-to-r2.mjs --key <object-key> --file <path>')
  }

  return values
}
