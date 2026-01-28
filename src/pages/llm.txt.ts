import type { APIRoute } from 'astro'
import { SITE_DESCRIPTION, SITE_TITLE, SITE_URL } from '@/consts'
import { getSortedPosts } from '@/utils'

export const GET: APIRoute = async () => {
  const posts = await getSortedPosts()

  const lines = [
    `# ${SITE_TITLE}`,
    '',
    `> ${SITE_DESCRIPTION}`,
    '',
    '## Posts',
    '',
    ...posts.map((post) => `- [${post.data.title}](${SITE_URL}/${post.data.link}.md)`),
  ]

  return new Response(lines.join('\n'), {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
    },
  })
}
