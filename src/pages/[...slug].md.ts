import type { APIRoute, GetStaticPaths } from 'astro'
import { getSortedPosts } from '@/utils'

export const getStaticPaths: GetStaticPaths = async () => {
  const posts = await getSortedPosts()
  return posts.map((post) => ({
    params: { slug: post.data.link },
    props: { post },
  }))
}

export const GET: APIRoute = async ({ props }) => {
  const { post } = props as { post: Awaited<ReturnType<typeof getSortedPosts>>[number] }

  const frontmatter = [
    '---',
    `title: ${post.data.title}`,
    `date: ${post.data.pubDate.toISOString()}`,
    post.data.description ? `description: ${post.data.description}` : null,
    '---',
  ].filter(Boolean).join('\n')

  const content = `${frontmatter}\n\n${post.body}`

  return new Response(content, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
    },
  })
}
