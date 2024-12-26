import Sitemap from '@astrojs/sitemap'
import { defineConfig } from 'astro/config'
import UnoCSS from 'unocss/astro'
import { SITE_URL } from './src/consts'

// https://astro.build/config
export default defineConfig({
  site: SITE_URL,
  integrations: [
    Sitemap(),
    UnoCSS({ injectReset: true }),
  ],
  markdown: {
    shikiConfig: {
      themes: {
        light: 'vitesse-light',
        dark: 'vitesse-dark',
      },
    },
  },
})
