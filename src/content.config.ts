import { createMarkdownProcessor } from '@astrojs/markdown-remark'
import { defineCollection, z } from 'astro:content'
import { Client } from 'sakuin'

const client = new Client()

const posts = defineCollection({
  loader: {
    name: 'xlog-loader',
    async load({ config, store }) {
      const markdownProcessor = await createMarkdownProcessor(config.markdown as any)
      const posts = await client.post.getAll('hyoban')
      for (const post of posts) {
        const { code: html } = await markdownProcessor.render(post.content)

        store.set({
          id: post.slug,
          data: {
            slug: post.slug,
            title: post.title,
            description: post.summary,
            date: new Date(post.publishedAt),
          },
          body: post.content,
          rendered: { html },
        })
      }
    },
  },
  schema: z.object({
    slug: z.string(),
    title: z.string(),
    description: z.string(),
    date: z.coerce.date(),
  }),
})

export const collections = { posts }
