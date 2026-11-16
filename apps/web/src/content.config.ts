import { glob } from 'astro/loaders'
import { z } from 'astro/zod'
import { defineCollection } from 'astro:content'
import { momentRecordSchema } from '@hyoban/data-spec'
import { readMomentRecords, readTripIds } from '@/data/store'

const description = z.string().trim().min(1)

const posts = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/posts' }),
  schema: z.object({
    title: z.string(),
    link: z.string(),
    description,
    ogImage: z.url({ protocol: /^https?$/ }).optional(),
    ogDescription: description.optional(),
    pubDate: z.coerce.date(),
  }),
})

const pages = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/pages' }),
  schema: z.object({
    title: z.string(),
    link: z.string(),
    description,
    ogImage: z.url({ protocol: /^https?$/ }).optional(),
    ogDescription: description.optional(),
  }),
})

// The recording app's Moments, in the first shape @hyoban/data-spec gives: the entries with
// words under data/ (or the API's snapshot when the build fetched one), as the store reads
// them from either layout.
const moments = defineCollection({
  // The trip a Moment names by its key, by its id on the site instead (trip-slugs.ts).
  loader: () => {
    const ids = readTripIds()
    return readMomentRecords().map(({ record }) => ({
      ...record,
      id: record.uid,
      ...(record.trip ? { trip: ids.get(record.trip) ?? record.trip } : {}),
    }))
  },
  schema: momentRecordSchema,
})

export const collections = { posts, pages, moments }
