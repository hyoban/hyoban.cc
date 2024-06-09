import { client } from '~/lib/client'
import { env } from '~/lib/env'

import { PostList } from './post-list'

export default async function HomePage() {
  const { list: posts, cursor } = await client.post.getMany(env.HANDLE, { translate: { to: 'zh' } })
  return <PostList posts={posts} cursor={cursor} />
}
