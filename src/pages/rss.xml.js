import rss from '@astrojs/rss'
import { SITE_DESCRIPTION, SITE_TITLE } from '@/consts'

export async function GET(context) {
  const postImportResult = import.meta.glob('../content/posts/**/*.md', { eager: true })
  const posts = Object.values(postImportResult)

  return rss({
    title: SITE_TITLE,
    description: SITE_DESCRIPTION,
    site: context.site,
    items: await Promise.all(
      posts.map(async post => ({
        ...post.frontmatter,
        link: `/${post.frontmatter.link}`,
        customData: `<guid>${post.frontmatter.link}</guid>`,
        content: (await post.compiledContent()),
      })),
    ),
    trailingSlash: false,
  })
}
