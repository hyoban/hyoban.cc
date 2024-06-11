import type { Post } from 'sakuin'

import { AppLink } from '~/components/app-link'
import { RelativeDate } from '~/components/post/relative-date'
import { TagList } from '~/components/post/tag-list'

export function PostItem({ post }: { post: Post }) {
  return (
    <AppLink
      href={`post/${post.slug}`}
      className="not-prose group my-6 flex flex-col"
    >
      <section className="my-4 flex items-center gap-2">
        <h2 className="font-medium">{post.title}</h2>
        <span className="i-lucide-arrow-right text-sm hidden group-hover:inline" />
      </section>
      <p className="opacity-70 line-clamp-3">
        {post.summary}
      </p>
      <div className="opacity-70 mt-4 flex gap-3 items-center">
        <RelativeDate date={post.updatedAt} />
        <TagList tags={post.tags} />
      </div>
    </AppLink>
  )
}
