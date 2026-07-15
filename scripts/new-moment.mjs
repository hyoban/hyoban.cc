import { fileURLToPath } from 'node:url'

import { createMoment } from './lib/moment-repository.mjs'

const root = fileURLToPath(new URL('../src/content/moments/', import.meta.url))
const result = await createMoment(root, { slug: process.argv[2] ?? 'note' })

console.log(result.documentPath)
