import { notFound } from 'next/navigation'

import { Comment } from '~/components/post/comment'
import { Markdown } from '~/components/post/markdown'
import { PageMeta, PostMeta } from '~/components/post/meta'
import { client } from '~/lib/client'
import { env } from '~/lib/env'
import { cn } from '~/lib/utils'

export async function generateStaticParams() {
  const { list: posts } = await client.post.getMany(env.HANDLE)
  const slugs = posts.map(post => post.slug)
  return slugs.map(slug => ({ slug }))
}

export default async function PostPage({
  params,
}: {
  params: { slug: string }
}) {
  const { HANDLE } = env
  const post = await client.post.getBySlug(HANDLE, params.slug)
  if (!post)
    notFound()

  return (
    <main
      className={cn(
        'mx-auto max-w-[692px] p-6 sm:py-16',
        'antialiased prose dark:prose-invert break-words prose-pre:shadow-sm prose-img:shadow-sm',
      )}
    >
      <PageMeta slug={params.slug} isPost />
      <article>
        <h1>{post.title}</h1>
        <PostMeta slug={params.slug} isPost />
        <Markdown content={post.content} />
      </article>
      <Comment noteId={post.noteId} />
    </main>
  )
}
