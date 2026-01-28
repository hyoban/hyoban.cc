import type { APIRoute } from 'astro'
import rss from '@astrojs/rss'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'
import { getSortedPosts } from '@/utils'

export const GET: APIRoute = async (context) => {
  const posts = await getSortedPosts()

  const response = await rss({
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    site: context.site!,
    items: posts.map(post => ({
      ...post.data,
      link: `/${post.data.link}`,
      customData: `<guid>${post.data.link}</guid>`,
      content: post.rendered?.html,
    })),
    trailingSlash: false,
  })

  response.headers.set('Content-Type', 'application/xml; charset=utf-8')

  return response
}
