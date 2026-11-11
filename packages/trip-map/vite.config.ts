import { defineConfig } from 'vite-plus'
import MagicString from 'magic-string'

// `vp pack` builds the publishable dist; the workspace imports the sources directly.
// The stylesheet is built after it by scripts/build-styles.mjs, with mapbox-gl's CSS
// inlined so a site needs no CSS tooling that resolves bare module paths. @hyoban/drawer,
// a dev dependency, is bundled in too; its types only come out with `eager`.
export default defineConfig({
  pack: {
    deps: { onlyBundle: ['@mapbox/tiny-sdf'] },
    dts: { eager: true },
    entry: {
      client: 'src/client.ts',
      dom: 'src/dom.ts',
      index: 'src/index.ts',
      'local-glyph-worker': 'src/map/local-glyph-worker.ts',
      preview: 'src/preview.ts',
    },
    format: 'esm',
    sourcemap: true,
    // Vite bundles the source worker URL for the site. The published library ships
    // it as its own entry instead, beside client.mjs and the other browser chunks.
    plugins: [
      {
        name: 'trip-map-glyph-worker-url',
        transform(code, id) {
          if (id.endsWith('/src/map/local-glyphs.ts')) {
            const from = "'./local-glyph-worker.ts'"
            const start = code.indexOf(from)

            if (start === -1) {
              throw new Error('The local glyph worker URL is missing.')
            }

            const source = new MagicString(code)

            source.overwrite(start, start + from.length, "'./local-glyph-worker.mjs'")
            return { code: source.toString(), map: source.generateMap({ hires: true }) }
          }

          return undefined
        },
      },
    ],
  },
})
