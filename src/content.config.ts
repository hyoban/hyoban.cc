import { defineCollection } from 'astro:content'
import { postLoader } from 'sakuin/astro'

const posts = defineCollection({
  loader: postLoader({ handle: 'hyoban' }),
})

export const collections = { posts }
