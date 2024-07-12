import { Box, Card, Flex, Text } from '@radix-ui/themes'
import type { Portfolio } from 'sakuin'

import { AppLink } from '~/components/app-link'
import { client } from '~/lib/client'
import { env } from '~/lib/env'

function capitalize(str: string) {
  return str.replaceAll(/\b\w/g, l => l.toUpperCase()).replaceAll('-', ' ')
}

function getSuperscript(portfolio: Portfolio) {
  if (portfolio.projectStarsCount)
    return `${portfolio.projectStarsCount} stars`

  if (portfolio.audioListensCount)
    return `${portfolio.audioListensCount} listens`

  if (portfolio.commentsCount)
    return `${portfolio.commentsCount} comments`

  if (portfolio.videoViewsCount)
    return `${portfolio.videoViewsCount} views`

  return ''
}

function ListItem({
  title,
  description,
  superscript,
  link,
}: {
  title: string
  description: string
  superscript?: string
  link: string
}) {
  return (
    <Flex direction="column" mx="-3" p="3" my="3" asChild>
      <Card asChild>
        <AppLink href={link} raw>
          <Flex gap="2">
            <Text>{title}</Text>
            <Box flexShrink="0" asChild>
              <Text size="1" color="gray">{superscript}</Text>
            </Box>
          </Flex>
          <Text as="p" mt="2" color="gray">{description}</Text>
        </AppLink>
      </Card>
    </Flex>
  )
}

export default async function PortfolioPage() {
  const portfolios = await client.portfolio.getAll(env.HANDLE)

  return (
    <>
      {portfolios.map(portfolio => (
        <ListItem
          key={portfolio.noteId}
          title={capitalize(portfolio.title)}
          description={portfolio.summary}
          link={portfolio.link}
          superscript={getSuperscript(portfolio)}
        />
      ))}
    </>
  )
}
