import type { APIRoute } from 'astro'
import rss from '@astrojs/rss'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'
import { getSortedPosts } from '@/utils'

export const GET: APIRoute = async (context) => {
  const posts = await getSortedPosts()

  return rss({
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    site: context.site!,
    items: await Promise.all(
      posts.map(async post => ({
        ...post.data,
        link: `/${post.data.link}`,
        customData: `<guid>${post.data.link}</guid>`,
        content: post.rendered?.html,
      })),
    ),
    trailingSlash: false,
  })
}
