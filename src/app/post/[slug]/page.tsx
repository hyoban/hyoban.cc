import { Box, Callout, Container, Heading, Text } from '@radix-ui/themes'
import { notFound } from 'next/navigation'

import { Comment } from '~/components/post/comment'
import { Markdown } from '~/components/post/markdown'
import { PageMeta, PostMeta } from '~/components/post/meta'
import { client } from '~/lib/client'
import { env } from '~/lib/env'

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
    <Container mx="auto" p="5" size="2">
      <PageMeta slug={params.slug} isPost />
      <article>
        <Heading size="8" my="6">{post.title}</Heading>
        <PostMeta slug={params.slug} isPost />
        <Callout.Root className="not-prose" my="4" mb="6">
          {!post.disableAISummary && (
            <>
              <Callout.Icon>
                <div className="i-lucide-info text-xs" />
              </Callout.Icon>
              <Callout.Text>
                AI generated summary
              </Callout.Text>
            </>
          )}
          <Text as="p" size="2" className="leading-6">
            {post.summary}
          </Text>
        </Callout.Root>
        <Box className="prose max-w-full dark:prose-invert break-words prose-pre:shadow-sm prose-img:shadow-sm">
          <Markdown content={post.content} />
        </Box>
      </article>
      <Comment noteId={post.noteId} />
    </Container>
  )
}
