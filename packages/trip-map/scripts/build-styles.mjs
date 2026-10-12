import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

// Writes dist/styles.css: the package's stylesheet with mapbox-gl's CSS in place of
// the import of it, so the published file stands on its own.

const require = createRequire(import.meta.url)
const importLine = /^@import 'mapbox-gl\/dist\/mapbox-gl\.css';\n/m
const own = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8')

if (!importLine.test(own)) {
  throw new Error('src/styles.css no longer imports mapbox-gl/dist/mapbox-gl.css.')
}

const mapbox = await readFile(require.resolve('mapbox-gl/dist/mapbox-gl.css'), 'utf8')
const { version } = require('mapbox-gl/package.json')

await mkdir(new URL('../dist/', import.meta.url), { recursive: true })
await writeFile(
  new URL('../dist/styles.css', import.meta.url),
  `/* mapbox-gl ${version} (https://github.com/mapbox/mapbox-gl-js/blob/main/LICENSE.txt) */\n${mapbox.trim()}\n\n${own.replace(importLine, '')}`,
)
console.log('dist/styles.css written')
