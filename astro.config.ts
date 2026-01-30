import Sitemap from '@astrojs/sitemap'
import vercel from '@astrojs/vercel'
import tailwindcss from '@tailwindcss/vite'
import { inferRemoteSize } from 'astro/assets/utils'
import { defineConfig } from 'astro/config'
import { visit } from 'unist-util-visit'
import { SITE_URL } from './src/consts'

// https://astro.build/config
export default defineConfig({
  site: SITE_URL,
  adapter: vercel(),
  integrations: [
    Sitemap(),
  ],
  vite: {
    plugins: [
      tailwindcss(),
    ],
  },
  markdown: {
    shikiConfig: {
      themes: {
        light: 'solarized-light',
        dark: 'tokyo-night',
      },
    },
    rehypePlugins: [
      () => {
        const imageNodes = new Set<any>()
        return (tree) => {
          visit(tree, 'element', (node) => {
            if (node.tagName === 'img') {
              imageNodes.add(node)
            }
          })

          return new Promise((resolve) => {
            const promises = Array.from(imageNodes).map(async (node) => {
              const src = node.properties.src as string | undefined
              if (!src || (!src.startsWith('http://') && !src.startsWith('https://'))) {
                return
              }
              const { width, height } = await inferRemoteSize(src)
              node.properties.width = width
              node.properties.height = height
            })

            Promise.all(promises).then(() => {
              resolve()
            })
          })
        }
      },
    ],
  },
})
