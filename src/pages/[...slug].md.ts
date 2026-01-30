import type { APIRoute, GetStaticPaths } from 'astro'
import { getSortedPosts } from '@/utils'

export const getStaticPaths: GetStaticPaths = async () => {
  const posts = await getSortedPosts()
  return posts.map(post => ({
    params: { slug: post.data.link },
    props: { post },
  }))
}

export const GET: APIRoute = async ({ props }) => {
  const { post } = props as { post: Awaited<ReturnType<typeof getSortedPosts>>[number] }

  const frontmatterLines = Object.entries(post.data).map(([key, value]) => {
    if (value instanceof Date) {
      return `${key}: ${value.toISOString()}`
    }
    return `${key}: ${JSON.stringify(value)}`
  })

  const content = `---\n${frontmatterLines.join('\n')}\n---\n\n${post.body}`

  return new Response(content, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
    },
  })
}
