import { unified } from '@astrojs/markdown-remark'
import Sitemap from '@astrojs/sitemap'
import { defineConfig } from 'astro/config'
import { SITE_URL } from './src/consts'
import { apiData } from './src/data/api-data.ts'
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
  },
  markdown: {
    processor: unified({
      remarkPlugins: [remarkTelegramWidgets, remarkTripCovers],
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
