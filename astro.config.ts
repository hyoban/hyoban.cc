import Sitemap from '@astrojs/sitemap'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'astro/config'
// import UnoCSS from 'unocss/vite'
import { SITE_URL } from './src/consts'

// https://astro.build/config
export default defineConfig({
  site: SITE_URL,
  integrations: [
    Sitemap(),
  ],
  vite: {
    plugins: [
      tailwindcss(),
      // UnoCSS(),
    ],
  },
  markdown: {
    shikiConfig: {
      themes: {
        light: 'solarized-light',
        dark: 'tokyo-night',
      },
    },
  },
})
