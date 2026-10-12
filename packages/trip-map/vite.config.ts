import { defineConfig } from 'vite-plus'

// `vp pack` builds the publishable dist; the workspace imports the sources directly.
// The stylesheet is built after it by scripts/build-styles.mjs, with mapbox-gl's CSS
// inlined so a site needs no CSS tooling that resolves bare module paths.
export default defineConfig({
  pack: {
    dts: true,
    entry: {
      client: 'src/client.ts',
      dom: 'src/dom.ts',
      index: 'src/index.ts',
      preview: 'src/preview.ts',
    },
    format: 'esm',
    sourcemap: true,
  },
})
