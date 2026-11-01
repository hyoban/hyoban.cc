import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { AstroIntegration } from 'astro'
import { loadEnv } from 'vite-plus'
import { DEFAULT_API_URL, fetchSnapshot, writeSnapshot } from './snapshot.ts'
import { DATA_ROOT_VARIABLE } from './store.ts'

// The site is built from the API, which needs MOMENTS_API_TOKEN, in the environment or the
// repository's .env: before anything reads a record, this fetches the API's export once
// and writes it under .artifacts/api-data in data/'s layout, and points the store and the
// Moments collection there. Without a token, or with one the API turns down, the build
// fails: the repository keeps no copy of the records to fall back on. A build given
// HYOBAN_DATA_ROOT already, as the tests give it a made-up data/, reads that instead.

export function apiData(): AstroIntegration {
  return {
    name: 'hyoban:api-data',
    hooks: {
      'astro:config:setup': async ({ command, config, logger }) => {
        if (process.env[DATA_ROOT_VARIABLE]) {
          logger.info(`Reading ${process.env[DATA_ROOT_VARIABLE]}`)
          return
        }

        const root = fileURLToPath(config.root)
        const envDir = typeof config.vite.envDir === 'string' ? config.vite.envDir : root
        const env = loadEnv(command === 'dev' ? 'development' : 'production', envDir, '')
        const token = env['MOMENTS_API_TOKEN']

        if (!token) {
          throw new Error(
            'The site is built from the API: set MOMENTS_API_TOKEN, in the environment or .env.',
          )
        }

        const url = env['MOMENTS_API_URL'] || DEFAULT_API_URL
        const snapshot = await fetchSnapshot(url, token)
        const directory = join(root, '.artifacts', 'api-data')

        await writeSnapshot(snapshot, directory)
        process.env[DATA_ROOT_VARIABLE] = directory
        logger.info(
          `Read revision ${snapshot.revision} from ${url}: ` +
            `${snapshot.entries?.length ?? 0} entries, ${snapshot.collections?.length ?? 0} collections, ${snapshot.tracks?.length ?? 0} tracks, ` +
            `${Object.keys(snapshot.places).length} places, ` +
            `${Object.keys(snapshot.routes ?? {}).length} routes`,
        )
      },
    },
  }
}
