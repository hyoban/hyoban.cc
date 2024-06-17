'use client'

import Link from 'next/link'

export function AppLink({ href, children, ...rest }: React.ComponentProps<'a'>) {
  if (!href)
    return <>{children}</>

  if (href.startsWith('http')) {
    return (
      <a href={href} target="_blank" rel="noreferrer noopener" {...rest}>
        {children}
      </a>
    )
  }

  return (
    <Link href={href} {...rest}>
      {children}
    </Link>
  )
}
