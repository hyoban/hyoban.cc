import { readFileSync } from 'node:fs'
import type { Plugin } from 'vite'

// The Worker serves public/_redirects' 200 rules as rewrites: one page for several
// addresses. The dev server doesn't, so this follows the same rules there.

export function devRewrites(file = 'public/_redirects'): Plugin {
  return {
    name: 'hyoban:dev-rewrites',
    apply: 'serve',
    configureServer(server) {
      // Read for each request, so a rule added meanwhile applies at once.
      const readRules = () =>
        readFileSync(file, 'utf8')
          .split('\n')
          .map((line) => line.trim().split(/\s+/))
          .filter(([from, , status]) => from && !from.startsWith('#') && status === '200')
          .map(([from, to]) => ({
            pattern: new RegExp(`^${from!.replaceAll(/:(\w+)/g, '(?<$1>[^/]+)')}$`),
            to: to!,
          }))

      server.middlewares.use((request, _response, next) => {
        const [pathname = '', query] = (request.url ?? '').split('?')

        for (const { pattern, to } of readRules()) {
          const groups = pathname.match(pattern)?.groups

          if (groups) {
            const target = to.replaceAll(/:(\w+)/g, (_, name: string) => groups[name] ?? '')
            request.url = query === undefined ? target : `${target}?${query}`
            break
          }
        }

        next()
      })
    },
  }
}
