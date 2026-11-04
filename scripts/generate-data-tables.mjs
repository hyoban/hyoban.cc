import { mkdir, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'

import {
  kotlinFile,
  modulesFile,
  renderKotlin,
  renderModules,
  renderSql,
  sqlFile,
} from './lib/data-tables.mjs'

// Writes @hyoban/data-spec/tables out for D1 (apps/api/schema.sql) and for the app's copy
// (CopyTables.kt), so both keep what leaves the phone in the same tables, and the spec's
// modules for the app (Modules.kt), so it turns on and off what the spec defines.

for (const [file, text] of [
  [sqlFile, renderSql()],
  [kotlinFile, renderKotlin()],
  [modulesFile, renderModules()],
]) {
  await mkdir(dirname(file), { recursive: true })
  await writeFile(file, text)
  console.log(`Wrote ${file}`)
}
