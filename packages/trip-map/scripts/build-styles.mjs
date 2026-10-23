import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

// Writes dist/styles.css: the package's stylesheet with mapbox-gl's CSS and the drawer's
// in place of the imports of them, so the published file stands on its own.

const require = createRequire(import.meta.url)
const importLine = /^@import 'mapbox-gl\/dist\/mapbox-gl\.css' layer\(components\);\n/m
const own = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8')

if (!importLine.test(own)) {
  throw new Error('src/styles.css no longer imports mapbox-gl/dist/mapbox-gl.css.')
}

const drawerLine = /^@import '@hyoban\/drawer\/styles\.css';\n/m

if (!drawerLine.test(own)) {
  throw new Error('src/styles.css no longer imports @hyoban/drawer/styles.css.')
}

const mapbox = await readFile(require.resolve('mapbox-gl/dist/mapbox-gl.css'), 'utf8')
// The drawer's, bundled into the client too, so the package needs no other to be installed.
const drawer = await readFile(require.resolve('@hyoban/drawer/styles.css'), 'utf8')
const { version } = require('mapbox-gl/package.json')

await mkdir(new URL('../dist/', import.meta.url), { recursive: true })
await writeFile(
  new URL('../dist/styles.css', import.meta.url),
  `/* mapbox-gl ${version} (https://github.com/mapbox/mapbox-gl-js/blob/main/LICENSE.txt), in a cascade layer so the page's styles win over it. */\n@layer components {\n${mapbox.trim()}\n}\n\n${drawer.trim()}\n\n${own.replace(importLine, '').replace(drawerLine, '')}`,
)
console.log('dist/styles.css written')
