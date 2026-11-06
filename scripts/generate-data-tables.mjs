import { mkdir, writeFile } from 'node:fs/promises'
import { dirname } from 'node:path'

import {
  kotlinFile,
  modulesFile,
  renderKotlin,
  renderModules,
  renderSql,
  renderSwift,
  renderSwiftModules,
  sqlFile,
  swiftFile,
  swiftModulesFile,
} from './lib/data-tables.mjs'

// Writes @hyoban/data-spec/tables out for D1 (apps/api/schema.sql) and for the app's copy
// (CopyTables.kt) and the Apple apps' (CopyTables.swift), so all keep what leaves the phone in
// the same tables, and the spec's modules for the apps (Modules.kt, Modules.swift), so they turn
// on and off what the spec defines.

for (const [file, text] of [
  [sqlFile, renderSql()],
  [kotlinFile, renderKotlin()],
  [modulesFile, renderModules()],
  [swiftFile, renderSwift()],
  [swiftModulesFile, renderSwiftModules()],
]) {
  await mkdir(dirname(file), { recursive: true })
  await writeFile(file, text)
  console.log(`Wrote ${file}`)
}
