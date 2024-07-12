'use client'

import type { LinkProps as RadixLinkProps } from '@radix-ui/themes'
import { HoverCard, Link as RadixLink } from '@radix-ui/themes'
import NextLink from 'next/link'

import { cn } from '~/lib/utils'

export function AppLink({
  href,
  children,
  className,
  raw,
  ...rest
}: RadixLinkProps & { raw?: boolean }) {
  if (!href)
    return <>{children}</>

  if (href.startsWith('http')) {
    if (raw) {
      return (
        <a
          href={href}
          className={cn('not-prose', className)}
          target="_blank"
          rel="noreferrer noopener"
          {...rest}
        >
          {children}
        </a>
      )
    }
    return (
      <HoverCard.Root>
        <HoverCard.Trigger>
          <RadixLink
            href={href}
            target="_blank"
            rel="noreferrer noopener"
            className={cn('not-prose', className)}
            {...rest}
          >
            {children}
          </RadixLink>
        </HoverCard.Trigger>
        <HoverCard.Content size="1" className="p-1">
          {href}
        </HoverCard.Content>
      </HoverCard.Root>
    )
  }

  if (raw) {
    return (
      <NextLink
        href={href}
        className={cn('not-prose', className)}
        {...rest}
      >
        {children}
      </NextLink>
    )
  }

  return (
    <RadixLink
      asChild
      href={href}
      className={cn('not-prose', className)}
      {...rest}
    >
      <NextLink href={href}>
        {children}
      </NextLink>
    </RadixLink>
  )
}
