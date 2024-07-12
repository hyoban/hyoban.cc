import { AppLink } from '~/components/app-link'
import { AppearanceSwitch } from '~/components/appearance-switch'
import { client } from '~/lib/client'
import { env } from '~/lib/env'

import { Navigation } from '../navigation'
import { getUniverseLinks } from '../utils'

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
    <main className="mx-auto max-w-[692px] p-6 sm:py-16 antialiased prose dark:prose-invert">
      <section>
        <h2 className="flex items-center gap-3">
          {characterName}
          <AppearanceSwitch className="scale-75" />
        </h2>
        <p>{description}</p>
        {links.length > 0 && (
          <section className="flex gap-4 items-center">
            {links.map(link => (
              <AppLink
                href={link.href}
                key={link.href}
                title={link.title}
                raw
              >
                {
                  link.icon
                    ? <div className={link.icon} />
                    : <div>{link.title}</div>
                }
              </AppLink>
            ))}
          </section>
        )}
      </section>
      <Navigation additionalNavigation={navigationLinks} />
      {children}
    </main>
  )
}
