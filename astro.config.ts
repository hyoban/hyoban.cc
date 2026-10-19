import { unified } from '@astrojs/markdown-remark'
import Sitemap from '@astrojs/sitemap'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'astro/config'
import { SITE_URL } from './src/consts'
import { rehypePostImages } from './src/post-images.mjs'
import { remarkTelegramWidgets } from './src/telegram-widget.mjs'
import { remarkTripCovers } from './src/trip-covers.ts'

// https://astro.build/config
export default defineConfig({
  site: SITE_URL,
  devToolbar: { enabled: false },
  // Map place pages are drawer fragments, not pages to list.
  integrations: [Sitemap({ filter: (page) => !page.includes('/map/place/') })],
  vite: {
    plugins: [tailwindcss() as any],
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
