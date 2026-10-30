import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

import { renderKotlin } from './src/kotlin.mjs'

// Writes the platforms' code from tokens.json and motion.json, the one source of the design's
// values: for now the Android app's ui/theme/Tokens.kt.

export const kotlinFile = fileURLToPath(
  new URL(
    '../../apps/android/app/src/main/kotlin/cc/hyoban/moments/ui/theme/Tokens.kt',
    import.meta.url,
  ),
)

export async function readTokens() {
  const read = async (name) => JSON.parse(await readFile(new URL(name, import.meta.url), 'utf8'))
  return { tokens: await read('./tokens.json'), motion: await read('./motion.json') }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { tokens, motion } = await readTokens()
  await writeFile(kotlinFile, renderKotlin(tokens, motion))
  console.log(`Wrote ${kotlinFile}`)
}
