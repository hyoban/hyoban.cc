'use client'

import { TabNav } from '@radix-ui/themes'
import Link from 'next/link'
import { usePathname } from 'next/navigation'

const navigation = [
  { href: '/', label: 'Posts' },
  { href: '/portfolios', label: 'Portfolios' },
  { href: '/shorts', label: 'Shorts' },
]

export function Navigation({
  additionalNavigation,
}: {
  additionalNavigation?: Array<{ href: string, label: string }>
}) {
  const pathname = usePathname()
  return (
    <TabNav.Root className="not-prose shadow-none" mx="-2">
      {[...navigation, ...(additionalNavigation ?? [])].map(({ href, label }) => (
        <TabNav.Link
          key={href}
          href={href}
          active={pathname === href}
          asChild
        >
          <Link href={href}>
            {label}
          </Link>
        </TabNav.Link>
      ))}
    </TabNav.Root>
  )
}
