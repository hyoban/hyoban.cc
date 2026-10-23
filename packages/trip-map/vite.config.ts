import { defineConfig } from 'vite-plus'

// `vp pack` builds the publishable dist; the workspace imports the sources directly.
// The stylesheet is built after it by scripts/build-styles.mjs, with mapbox-gl's CSS
// inlined so a site needs no CSS tooling that resolves bare module paths. @hyoban/drawer,
// a dev dependency, is bundled in too; its types only come out with `eager`.
export default defineConfig({
  pack: {
    dts: { eager: true },
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
