import { unified } from '@astrojs/markdown-remark'
import Sitemap from '@astrojs/sitemap'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig, passthroughImageService } from 'astro/config'
import { SITE_URL } from './src/consts'
import { remarkTelegramWidgets } from './src/telegram-widget.mjs'

// https://astro.build/config
export default defineConfig({
  site: SITE_URL,
  devToolbar: { enabled: false },
  image: {
    service: passthroughImageService(),
  },
  integrations: [
    Sitemap(),
  ],
  vite: {
    plugins: [
      tailwindcss() as any,
    ],
  },
  markdown: {
    processor: unified({
      remarkPlugins: [
        remarkTelegramWidgets,
      ],
    }),
    shikiConfig: {
      themes: {
        light: 'vitesse-light',
        dark: 'vitesse-dark',
      },
    },
  },
})
