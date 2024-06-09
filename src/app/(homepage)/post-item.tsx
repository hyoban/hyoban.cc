import { formatDistance } from 'date-fns'
import type { Post } from 'sakuin'

import { InteractionView } from '~/components/post/interaction'
import { TagList } from '~/components/post/tag-list'

import { AppLink } from '../external-link'

interface PostItemProps {
  post: Post
}

export function PostItem({ post }: PostItemProps) {
  return (
    <AppLink
      href={`post/${post.slug}`}
      className="not-prose group my-6 flex flex-col"
    >
      <PostDetail post={post} fullSummary />
    </AppLink>
  )
}

function PostDetail({
  post,
  fullSummary,
}: {
  post: Post
  fullSummary?: boolean
}) {
  return (
    <>
      <section className="text-2xl font-medium my-4 flex items-center gap-2">
        <h2>{post.title}</h2>
        <span className="i-lucide-arrow-right text-sm hidden group-hover:inline" />
      </section>
      <p className="opacity-90 text-[0.9rem] tracking-wide">
        {post.summary.length > 100 && !fullSummary
          ? `${post.summary.slice(0, 100)}...`
          : post.summary}
      </p>
      <div className="opacity-80 mt-4 text-sm flex gap-3 items-center">
        <span
          title={new Date(post.publishedAt).toLocaleString()}
        >
          {formatDistance(
            new Date(post.publishedAt),
            new Date(),
            { addSuffix: true },
          )}
        </span>
        <TagList tags={post.tags} />
        <InteractionView interaction={post} className="text-sm" />
      </div>
    </>
  )
}
