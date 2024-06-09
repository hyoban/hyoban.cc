import { Suspense } from 'react'

import { client } from '~/lib/client'
import { env } from '~/lib/env'

import { CommentListClient } from './comment-client'

export function Comment({ noteId }: { noteId: number }) {
  return (
    <section>
      <h2>Comments</h2>
      <Suspense fallback="Loading comments...">
        <CommentList noteId={noteId} />
      </Suspense>
    </section>
  )
}

async function CommentList({ noteId }: { noteId: number }) {
  const comments = await client.comment.getAll(env.HANDLE, noteId)
  return <CommentListClient comments={comments} noteId={noteId} />
}
