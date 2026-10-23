import { readFile, readdir, mkdir, writeFile, rename } from 'node:fs/promises'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

import { canonicalJson, convertZeekrCaptures, hashBytes } from './lib/zeekr-data.mjs'
import { assertPublicZeekrCapture } from './lib/zeekr-privacy.mjs'

const root = fileURLToPath(new URL('../data/zeekr/', import.meta.url))
const checkOnly = process.argv.includes('--check')
if (process.argv.slice(2).some((arg) => arg !== '--check'))
  throw new Error('Usage: convert-zeekr-data.mjs [--check]')
const imports = JSON.parse(await readFile(join(root, 'imports.json'), 'utf8'))
const devices = JSON.parse(await readFile(new URL('../data/devices.json', import.meta.url), 'utf8'))
const inputs = await Promise.all(
  imports.map(async (input) => {
    if (!devices[input.deviceId]) throw new Error(`Unknown device: ${input.deviceId}`)
    const path = resolve(root, input.file)
    if (!path.startsWith(resolve(root) + sep))
      throw new Error(`Source outside archive: ${input.file}`)
    const bytes = await readFile(path)
    assertPublicZeekrCapture(JSON.parse(bytes.toString()))
    return { ...input, bytes }
  }),
)
const { files, validation } = convertZeekrCaptures(inputs)
const failed = validation.checks.filter((check) => !check.passed)
if (failed.length) throw new Error(`Source validation failed:\n${JSON.stringify(failed, null, 2)}`)
files.set('model-validation.json', {
  captures: validation.captures,
  counts: validation.counts,
  checksPassed: validation.checks.length,
  warnings: validation.warnings,
  omissions: validation.omissions,
})
// Preflight before writing anything. Never silently delete an older generated record.
for (const directory of ['months', 'captures', 'assisted-driving']) {
  const existing = await readdir(join(root, directory), { recursive: true }).catch((error) => {
    if (error.code === 'ENOENT') return []
    throw error
  })
  for (const file of existing) {
    if (file.endsWith('.json') && !files.has(`${directory}/${file}`))
      throw new Error(`Unregistered generated record: ${directory}/${file}`)
  }
}
for (const [file, value] of files) {
  const path = join(root, file),
    text = canonicalJson(value)
  if (checkOnly) {
    if ((await readFile(path, 'utf8')) !== text)
      throw new Error(`Converted data is stale or altered: ${file}`)
  } else {
    await mkdir(dirname(path), { recursive: true })
    await writeFile(path + '.tmp', text)
    await rename(path + '.tmp', path)
  }
}
for (const input of inputs) {
  if (hashBytes(await readFile(join(root, input.file))) !== input.sha256)
    throw new Error(`Raw file changed during conversion: ${input.file}`)
}
console.log(`${checkOnly ? 'Verified' : 'Converted'}: ${JSON.stringify(validation.counts)}`)
console.log(
  `${validation.checks.length} checks passed; ${validation.warnings.length} source warnings retained.`,
)
