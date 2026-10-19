import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { AstroIntegration } from 'astro'
import { loadEnv } from 'vite-plus'
import { DEFAULT_API_URL, fetchSnapshot, writeSnapshot } from './snapshot.ts'
import { DATA_ROOT_VARIABLE } from './store.ts'

// With MOMENTS_API_TOKEN set, in the environment or .env, the site is built from the
// API rather than data/: before anything reads a record, this fetches the API's export
// once and writes it under .artifacts/api-data in data/'s layout, and points the store
// and the Moments collection there. Without a token everything reads data/, as the
// scripts always do. A token that fails to fetch fails the build instead of quietly
// falling back to the committed copy.

export function apiData(): AstroIntegration {
  return {
    name: 'hyoban:api-data',
    hooks: {
      'astro:config:setup': async ({ command, config, logger }) => {
        const root = fileURLToPath(config.root)
        const env = loadEnv(command === 'dev' ? 'development' : 'production', root, '')
        const token = env['MOMENTS_API_TOKEN']

        if (!token) {
          logger.info('No MOMENTS_API_TOKEN, reading data/')
          return
        }

        const url = env['MOMENTS_API_URL'] || DEFAULT_API_URL
        const snapshot = await fetchSnapshot(url, token)
        const directory = join(root, '.artifacts', 'api-data')

        await writeSnapshot(snapshot, directory)
        process.env[DATA_ROOT_VARIABLE] = directory
        logger.info(
          `Read revision ${snapshot.revision} from ${url}: ${snapshot.moments.length} Moments, ` +
            `${Object.keys(snapshot.places).length} places, ${snapshot.trips.length} trips`,
        )
      },
    },
  }
}
