import rehypeShiki from '@shikijs/rehype'
import type { ImageProps } from 'next/image'
import Image from 'next/image'
import { MDXRemote } from 'next-mdx-remote/rsc'
import { Tweet } from 'react-tweet'
import rehypeRaw from 'rehype-raw'
import remarkGfm from 'remark-gfm'
import remarkGithubAlerts from 'remark-github-alerts'
import remarkParse from 'remark-parse'

import { AppLink } from '../../app/external-link'
import { getImageDimensionByUri } from '../../app/utils'
import { GitHubCard } from './github-card'
import { rehypeEmbed, transformers } from './rehype-embed'

export function Markdown({ content }: { content: string }) {
  return (
    <MDXRemote
      source={content}
      components={{
        'a': AppLink,
        'img': async (props) => {
          if (!props.src)
            return null

          const size = await getImageDimensionByUri(props.src)
          if (!size)
            return <img {...props} />
          return (
            <Image
              width={size.width}
              height={size.height}
              {...(props as ImageProps)}
            />
          )
        },
        'tweet': ({ id }: { id: string }) => (
          <div className="not-prose">
            <Tweet id={id} />
          </div>
        ),
        'github-repo': ({ repo }: { repo: string }) => (
          <GitHubCard repo={repo} />
        ),
      }}
      options={{
        mdxOptions: {
          remarkPlugins: [remarkParse, remarkGithubAlerts, remarkGfm],
          rehypePlugins: [
            rehypeRaw,
            [rehypeEmbed, { transformers }],
            [
              rehypeShiki,
              { themes: { light: 'vitesse-light', dark: 'vitesse-dark' } },
            ],
          ],
          format: 'md',
        },
      }}
    />
  )
}
