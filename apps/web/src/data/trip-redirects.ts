import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import type { AstroIntegration } from 'astro'
import { readTripIds } from './store.ts'

// A trip's address follows its title (trip-slugs.ts), so the addresses it was known by before
// lead to the one it has now: its key in the data, `/trip/2026-yunnan/` or a phone's
// `/trip/01m7…/`, and that key by its year, `/trip/2026/yunnan/`: each one rule taking the trip
// and everything under it (playing, from a stop or not) to the same under its id now, which the
// site serves too, its page naming its address by year as canonical. Cloudflare takes at most
// 100 rules in a _redirects and counted every rule against it, so these stay one an address.
// They go first, ahead of the rewrites that would read them as trips' own pages.

/** The most rules Cloudflare takes in a _redirects, less the site's own. */
const MAX_RULES = 90

/** The redirects from each trip's old addresses to its address now, as _redirects lines. */
export function getTripRedirects(ids: ReadonlyMap<string, string> = readTripIds()) {
  const lines: string[] = []

  for (const [key, id] of ids) {
    if (key === id) continue

    const [, year, rest] = key.match(/^(\d{4})-(.+)$/) ?? []
    const olds = [`/trip/${key}/`, ...(year && rest ? [`/trip/${year}/${rest}/`] : [])]

    for (const old of olds) {
      lines.push(`${old}* /trip/${encodeURIComponent(id)}/:splat 301`)
    }
  }

  if (lines.length > MAX_RULES) {
    throw new Error(`${lines.length} trip redirects, more than Cloudflare takes (${MAX_RULES}).`)
  }

  return lines
}

/** Puts the trips' redirects at the top of the built _redirects. */
export function tripRedirects(): AstroIntegration {
  return {
    name: 'hyoban:trip-redirects',
    hooks: {
      'astro:build:done': async ({ dir, logger }) => {
        const file = fileURLToPath(new URL('_redirects', dir))
        const rules = await readFile(file, 'utf8')
        const lines = getTripRedirects()

        await writeFile(
          file,
          `# Each trip's old addresses, to its address by its title now.\n${lines.join('\n')}\n\n${rules}`,
        )
        logger.info(`Redirected ${lines.length} old trip addresses`)
      },
    },
  }
}
