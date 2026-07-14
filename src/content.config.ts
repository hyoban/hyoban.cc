import { glob } from 'astro/loaders'
import { z } from 'astro/zod'
import { defineCollection } from 'astro:content'

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

const momentMedia = z.object({
  alt: z.string(),
  file: z.string().trim().min(1),
  poster: z.string().trim().min(1).optional(),
  type: z.enum(['image', 'video']),
})

const moments = defineCollection({
  loader: glob({ pattern: '**/index.md', base: './src/content/moments' }),
  schema: z.object({
    hidden: z.boolean().default(false),
    media: z.array(momentMedia).default([]),
    occurredOn: z.iso.date().optional(),
    publishedAt: z.coerce.date(),
    sourceUrl: z.url().optional(),
  }).superRefine((moment, context) => {
    if (moment.sourceUrl) {
      return
    }

    for (const [index, media] of moment.media.entries()) {
      if (!media.alt.trim()) {
        context.addIssue({
          code: 'custom',
          message: 'New moment media requires alt text.',
          path: ['media', index, 'alt'],
        })
      }
    }
  }),
})

export const collections = { posts, pages, moments }
