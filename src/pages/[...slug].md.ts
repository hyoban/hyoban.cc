import type { APIRoute, GetStaticPaths } from 'astro'
import { getMarkdownEntries } from '@/utils'

export const getStaticPaths: GetStaticPaths = async () => {
  const entries = await getMarkdownEntries()
  return entries.map(entry => ({
    params: { slug: entry.data.link },
    props: { entry },
  }))
}

export const GET: APIRoute = async ({ props }) => {
  const { entry } = props as { entry: Awaited<ReturnType<typeof getMarkdownEntries>>[number] }

  const frontmatterLines = Object.entries(entry.data).map(([key, value]) => {
    if (value instanceof Date) {
      return `${key}: ${value.toISOString()}`
    }
    return `${key}: ${JSON.stringify(value)}`
  })

  const content = `---\n${frontmatterLines.join('\n')}\n---\n\n${entry.body}`

  return new Response(content, {
    headers: {
      'Content-Type': 'text/plain; charset=utf-8',
    },
  })
}
