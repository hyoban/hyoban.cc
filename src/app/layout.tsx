// eslint-disable-next-line @cspell/spellchecker
/* eslint-disable @eslint-react/dom/no-dangerously-set-innerhtml */
import 'remark-github-alerts/styles/github-base.css'
import 'remark-github-alerts/styles/github-colors-light.css'
import 'remark-github-alerts/styles/github-colors-dark-class.css'
import './globals.css'

import { Theme, ThemePanel } from '@radix-ui/themes'
import NextTopLoader from 'nextjs-toploader'
import Balancer from 'react-wrap-balancer'

import { AppearanceSwitch } from '~/components/appearance-switch'
import { client } from '~/lib/client'
import { env } from '~/lib/env'

export default async function RootLayout({ children }: React.PropsWithChildren) {
  const { HANDLE, SITE_URL } = env
  const {
    siteName,
    characterName,
    description,
    icon,
    banner,
    xlogUrl,
    footer,
  } = await client.site.getInfo(HANDLE)
  const title = siteName ?? characterName

  return (
    <html lang="en" suppressHydrationWarning className="h-full bg-background">
      <title>{title}</title>
      <meta name="description" content={description} />
      {icon && <link rel="icon" type="image/png" href={icon} />}
      <link rel="alternate" type="application/rss+xml" title={title} href={`${xlogUrl}/feed`} />
      <link rel="alternate" type="application/rss+xml" title={`Comments on ${title}`} href={`${xlogUrl}/feed/comments`} />
      <link rel="alternate" type="application/feed+json" title={title} href={`${xlogUrl}/feed?format=json`} />
      <link rel="alternate" type="application/feed+json" title={`Comments on ${title}`} href={`${xlogUrl}/feed/comments?format=json`} />

      <meta property="og:title" content={title} />
      <meta name="twitter:title" content={title} />
      <meta name="description" content={description} />
      <meta property="og:description" content={description} />
      <meta name="twitter:description" content={description} />
      <meta name="twitter:card" content="summary_large_image" />
      {SITE_URL && <meta property="og:url" content={SITE_URL} />}
      {banner && (
        <>
          <meta property="og:image" content={banner} />
          <meta name="twitter:image" content={banner} />
        </>
      )}
      <body className="h-full">
        <script
          dangerouslySetInnerHTML={{
            __html: `
              !(function () {
                var e = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches,
                  t = localStorage.getItem('use-dark') || '"system"',
                  n = '"dark"' === t || (e && '"light"' !== t);
                document.documentElement.classList.toggle("dark", n);
                document.documentElement.classList.toggle("light", !n);
              })()
            `,
          }}
        />
        <Theme accentColor="indigo" radius="large">
          <NextTopLoader color="var(--gray-11)" height={2} showSpinner={false} />
          {children}
          {footer && (
            <footer className="mx-6 flex flex-col gap-4 items-center opacity-80 text-sm mb-6 text-center">
              <Balancer className="max-w-[670px]">{footer}</Balancer>
            </footer>
          )}
          <AppearanceSwitch className="hidden" />
          <ThemePanel defaultOpen={false} />
        </Theme>
      </body>
    </html>
  )
}
