import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

import { renderKotlin } from './src/kotlin.mjs'
import { renderSwift } from './src/swift.mjs'

// Writes the platforms' code from tokens.json and motion.json, the one source of the design's
// values: the Android app's ui/theme/Tokens.kt and the iOS app's Design/Tokens.swift.

export const kotlinFile = fileURLToPath(
  new URL(
    '../../apps/android/app/src/main/kotlin/cc/hyoban/moments/ui/theme/Tokens.kt',
    import.meta.url,
  ),
)

export const swiftFile = fileURLToPath(
  new URL('../../apps/ios/Here/Design/Tokens.swift', import.meta.url),
)

export async function readTokens() {
  const read = async (name) => JSON.parse(await readFile(new URL(name, import.meta.url), 'utf8'))
  return { tokens: await read('./tokens.json'), motion: await read('./motion.json') }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { tokens, motion } = await readTokens()
  for (const [file, text] of [
    [kotlinFile, renderKotlin(tokens, motion)],
    [swiftFile, renderSwift(tokens, motion)],
  ]) {
    await writeFile(file, text)
    console.log(`Wrote ${file}`)
  }
}
