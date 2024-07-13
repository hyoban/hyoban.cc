import { Container, Flex, Heading, Section, Text } from '@radix-ui/themes'
import type { Navigation, SocialPlatform } from 'sakuin'
import { platforms } from 'sakuin'

import { AppLink } from '~/components/app-link'
import { AppearanceSwitch } from '~/components/appearance-switch'
import { client } from '~/lib/client'
import { env } from '~/lib/env'
import { cn } from '~/lib/utils'

import { Navigation as Nav } from './navigation'

interface Link {
  href: string
  title: string
  icon?: string
}

// eslint-disable-next-line max-params
function getUniverseLinks(
  connectedAccounts: SocialPlatform[] = [],
  navigationList: Navigation[] = [],
  xlogUrl = '',
  siteUrl = '',
) {
  return [
    ...connectedAccounts.map((account) => {
      const { platform, id } = account

      return {
        href: platforms[platform]?.url?.replace('{username}', id),
        title: platforms[platform]?.name.toLocaleLowerCase(),
        icon: platforms[platform]?.icon,
      }
    }),
    ...navigationList
      .filter(nav => nav.url.startsWith('http') && nav.url !== siteUrl)
      .map(nav => ({
        href: nav.url,
        title: nav.label.toLowerCase(),
        icon: undefined,
      })),
    {
      href: xlogUrl,
      title: 'blog',
      icon: 'i-lucide-book',
    },
  ]
    .filter(Boolean)
    .filter(link => link.href && link.title)
    .sort((a, b) => {
      // icon first
      if (a.icon && !b.icon)
        return -1
      if (!a.icon && b.icon)
        return 1
      // then title
      return a.title?.localeCompare(b.title ?? '') ?? 0
    }) as Link[]
}

export default async function HomeLayout({ children }: React.PropsWithChildren) {
  const { HANDLE, SITE_URL } = env
  const { characterName, description, socialPlatforms, navigation, xlogUrl }
    = await client.site.getInfo(HANDLE)
  const pages = await client.page.getAll(HANDLE)
  const navigationLinks = pages.map(page => ({
    href: `/${page.slug}`,
    label: page.title,
  }))

  const links = getUniverseLinks(
    socialPlatforms,
    navigation,
    xlogUrl,
    SITE_URL,
  )

  return (
    <Container mx="auto" p="5" size="2">
      <Section size="1" className="space-y-4">
        <Flex align="center" gap="3" asChild>
          <Heading>
            {characterName}
            <AppearanceSwitch className="scale-75" />
          </Heading>
        </Flex>
        <Text as="p">{description}</Text>
        {links.length > 0 && (
          <Flex gap="4" align="center">
            {links.map(link => (
              <AppLink
                href={link.href}
                key={link.href}
                title={link.title}
                raw
                className={cn(link.icon && 'size-5')}
              >
                {
                  link.icon
                    ? <div className={link.icon} />
                    : <div>{link.title}</div>
                }
              </AppLink>
            ))}
          </Flex>
        )}
      </Section>
      <Nav additionalNavigation={navigationLinks} />
      {children}
    </Container>
  )
}
