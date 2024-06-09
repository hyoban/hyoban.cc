'use client'

import Link from 'next/link'

type AppLinkProps = React.AnchorHTMLAttributes<HTMLAnchorElement>

export function AppLink({
  href,
  children,
  ...props
}: AppLinkProps) {
  if (!href)
    return <>{children}</>

  if (href.startsWith('http')) {
    return (
      <a
        {...props}
        target="_blank"
        rel="noreferrer noopener"
        href={href}
      >
        {children}
      </a>
    )
  }

  return (
    <Link {...props} href={href}>
      {children}
    </Link>
  )
}
