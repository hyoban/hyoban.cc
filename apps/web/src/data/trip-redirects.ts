import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import type { AstroIntegration } from 'astro'
import { getTripPath } from '../trip-path.ts'
import { readTripIds } from './store.ts'

// A trip's address follows its title (trip-slugs.ts), so the addresses it was known by before
// lead to the one it has now: its key in the data, `/trip/2026-yunnan/` or a phone's
// `/trip/01m7…/`, and that key by its year, `/trip/2026/yunnan/`, playing too. They go first
// in the built _redirects, ahead of the rewrites that would read them as trips' own pages.

/** The redirects from each trip's old addresses to its address now, as _redirects lines. */
export function getTripRedirects(ids: ReadonlyMap<string, string> = readTripIds()) {
  const lines: string[] = []

  for (const [key, id] of ids) {
    if (key === id) continue

    const to = getTripPath(id)
    const play = getTripPath(id, {})
    const [, year, rest] = key.match(/^(\d{4})-(.+)$/) ?? []
    const olds = [`/trip/${key}/`, ...(year && rest ? [`/trip/${year}/${rest}/`] : [])]

    for (const old of olds) {
      lines.push(`${old} ${to} 301`, `${old}play/ ${play} 301`, `${old}play/* ${play}:splat 301`)
    }
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
        logger.info(`Redirected ${lines.length / 3} old trip addresses`)
      },
    },
  }
}
