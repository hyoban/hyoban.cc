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
    // The map's drawer and Moments fragments aren't pages to list.
    Sitemap({ filter: (page) => !/\/map\/(.*\/moments|places\/\d+)\/$/.test(page) }),
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
