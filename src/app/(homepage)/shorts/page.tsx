import { Card, Flex } from '@radix-ui/themes'
import Image from 'next/image'
import type { Short } from 'sakuin'

import { getImageDimensionByUri } from '~/app/utils'
import { AppLink } from '~/components/app-link'
import { client } from '~/lib/client'
import { env } from '~/lib/env'

export default async function ShortPage() {
  const { list: shorts } = await client.short.getMany(env.HANDLE)

  return (
    <div className="columns-3xs space-y-4">
      {shorts.map(short => (
        <ShortItem key={short.slug} short={short} />
      ))}
    </div>
  )
}

async function ShortItem({ short }: { short: Short }) {
  const photos = short.attachments?.at(0)?.address
  if (!photos)
    return null

  const { xlogUrl } = await client.site.getInfo(env.HANDLE)

  const size = await getImageDimensionByUri(photos)
  return (
    <Flex direction="column" p="0" asChild>
      <Card asChild>
        <AppLink
          href={`${xlogUrl}/${short.slug}`}
          key={short.slug}
          raw
          className="overflow-hidden"
        >
          <Image
            src={photos}
            alt={short.content}
            width={size?.width}
            height={size?.height}
          />
          <div className="px-2 py-3 space-y-2">
            <p>{short.title || short.content}</p>
          </div>
        </AppLink>
      </Card>
    </Flex>
  )
}
