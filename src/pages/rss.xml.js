import rss from '@astrojs/rss'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'
import { getSortedPosts } from '@/utils'

export async function GET(context) {
  const posts = await getSortedPosts()
  return rss({
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
}
