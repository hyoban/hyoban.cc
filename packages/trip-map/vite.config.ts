import { defineConfig } from 'vite-plus'

// `vp pack` builds the publishable dist; the workspace imports the sources directly.
export default defineConfig({
  pack: {
    copy: [{ from: 'src/styles.css', to: 'dist/styles.css' }],
    dts: true,
    entry: {
      client: 'src/client.ts',
      dom: 'src/player/dom.ts',
      index: 'src/index.ts',
      preview: 'src/preview.ts',
    },
    format: 'esm',
  },
})
