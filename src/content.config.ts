import { glob } from 'astro/loaders'
import { z } from 'astro/zod'
import { defineCollection } from 'astro:content'
import { momentFrontmatterSchema } from '@/moment-content'

const description = z.string().trim().min(1)

const posts = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/posts' }),
  schema: z.object({
    title: z.string(),
    link: z.string(),
    description,
    pubDate: z.coerce.date(),
  }),
})

const pages = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/pages' }),
  schema: z.object({
    title: z.string(),
    link: z.string(),
    description,
  }),
})

const moments = defineCollection({
  loader: glob({ pattern: '**/index.md', base: './src/content/moments' }),
  schema: momentFrontmatterSchema,
})

export const collections = { posts, pages, moments }
