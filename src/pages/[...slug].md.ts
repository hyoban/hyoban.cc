import type { APIRoute, GetStaticPaths } from 'astro'

const posts = import.meta.glob('../content/posts/**/*.md', {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>

const postEntries = Object.entries(posts).map(([path, content]) => {
  const link = path.replace('../content/posts/', '').replace('.md', '')
  return { link, content }
})

export const getStaticPaths: GetStaticPaths = async () => {
  return postEntries.map(({ link, content }) => ({
    params: { slug: link },
    props: { content },
  }))
}

export const GET: APIRoute = async ({ props }) => {
  const { content } = props as { content: string }

  return new Response(content, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
    },
  })
}
