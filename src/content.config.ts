import { glob } from 'astro/loaders'
import { z } from 'astro/zod'
import { defineCollection } from 'astro:content'
import { momentRecordSchema } from '@hyoban/data-spec'
import { getDataRoot } from '@/data/store'

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

// The recording app's Moments, one JSON file each, in the shape @hyoban/data-spec gives:
// data/moments, or the API's snapshot when the build fetched one.
const moments = defineCollection({
  loader: glob({ pattern: '**/*.json', base: `${getDataRoot()}/moments` }),
  schema: momentRecordSchema,
})

export const collections = { posts, pages, moments }
