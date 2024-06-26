'use client'

import { clsx } from 'clsx'
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
    <nav className="not-prose flex flex-wrap gap-4 my-6">
      {navigation.map(({ href, label }) => (
        <Link
          key={href}
          href={href}
          className={clsx(
            pathname === href
              ? 'font-medium underline underline-offset-4'
              : 'opacity-70',
            'hover:opacity-100',
          )}
        >
          {label}
        </Link>
      ))}
      {additionalNavigation?.map(({ href, label }) => (
        <Link
          key={href}
          href={href}
          className={clsx(
            pathname === href
              ? 'font-medium underline underline-offset-4'
              : 'opacity-70',
            'hover:opacity-100',
          )}
        >
          {label}
        </Link>
      ))}
    </nav>
  )
}
