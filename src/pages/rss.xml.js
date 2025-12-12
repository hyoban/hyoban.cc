import rss from '@astrojs/rss'
import { getCollection } from 'astro:content'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'

export async function GET(context) {
  const posts = await getCollection('posts')
  const response = await rss({
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    site: context.site,
    items: posts.map(post => ({
      ...post.data,
      link: `/${post.data.link}`,
      customData: `<guid>${post.data.link}</guid>`,
    })),
    trailingSlash: false,
  })
  response.headers.set('Content-Type', 'application/rss+xml; charset=utf-8')

  return response
}
