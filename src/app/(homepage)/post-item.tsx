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
        <span className="i-lucide-arrow-right text-sm opacity-0 -translate-x-full group-hover:opacity-100 group-hover:translate-x-0 transition-transform" />
      </section>
      <p className="text-gray-900 line-clamp-3">
        {post.summary}
      </p>
      <div className="text-gray-900 mt-4 flex gap-3 items-center">
        <RelativeDate date={post.publishedAt} />
        <TagList tags={post.tags} />
      </div>
    </AppLink>
  )
}
