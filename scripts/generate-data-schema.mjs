import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { getDataSchemas, getSchemaFile, renderSchema } from './lib/data-schema.mjs'

// Writes @hyoban/data-spec out as JSON Schema under data/schema, for the recording app
// and anything else that writes data/ without this repository's TypeScript.

const root = fileURLToPath(new URL('../', import.meta.url))
const directory = join(root, 'data', 'schema')
const schemas = Object.entries(getDataSchemas())

await mkdir(directory, { recursive: true })

for (const [name, schema] of schemas) {
  await writeFile(join(directory, getSchemaFile(name)), renderSchema(name, schema))
}

console.log(`Wrote ${schemas.length} schemas to ${directory}`)
