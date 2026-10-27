import { unified } from '@astrojs/markdown-remark'
import Sitemap from '@astrojs/sitemap'
import { defineConfig } from 'astro/config'
import { SITE_URL } from './src/consts'
import { apiData } from './src/data/api-data.ts'
import { remarkCarModel } from './src/car-model.ts'
import { devRewrites } from './src/dev-rewrites.ts'
import { rehypePostImages } from './src/post-images.mjs'
import { remarkTelegramWidgets } from './src/telegram-widget.mjs'
import { remarkTripCovers } from './src/trip-covers.ts'

// https://astro.build/config
export default defineConfig({
  site: SITE_URL,
  devToolbar: { enabled: false },
  integrations: [
    // First, so every later read of a record sees the API's snapshot when there is one.
    apiData(),
    // The map's and trips' drawer and Moments fragments aren't pages to list, nor the pages
    // whose canonical is another's: a trip by its year and the rest, /trip/2026/yunnan/, and
    // /calendar/, the latest month.
    Sitemap({
      filter: (page) =>
        !/\/(calendar|map\/(.*\/moments|places\/\d+)|trip\/[^/]+\/moments|trip\/\d{4}\/(?!\d{2}\/$)[^/]+(\/play)?)\/$/.test(
          page,
        ),
    }),
  ],
  vite: {
    plugins: [devRewrites()],
    // The car loads three.js on demand; bundling its pieces up front saves the dev server
    // finding them mid-page and throwing the page's modules away to rebundle.
    optimizeDeps: {
      include: [
        'three',
        'three/examples/jsm/loaders/GLTFLoader.js',
        'three/examples/jsm/libs/meshopt_decoder.module.js',
        'three/examples/jsm/controls/OrbitControls.js',
        'three/examples/jsm/environments/RoomEnvironment.js',
      ],
    },
  },
  markdown: {
    processor: unified({
      remarkPlugins: [remarkTelegramWidgets, remarkTripCovers, remarkCarModel],
      rehypePlugins: [rehypePostImages],
    }),
    shikiConfig: {
      themes: {
        light: 'vitesse-light',
        dark: 'vitesse-dark',
      },
    },
  },
})
