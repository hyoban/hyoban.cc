import { fileURLToPath } from 'node:url'

import { modules } from '@hyoban/data-spec/entry'
import { copiedTables, getTableStatements } from '@hyoban/data-spec/tables'

// What `vp run data:tables` writes from @hyoban/data-spec: the tables as SQL for D1 and
// those the apps copy as Kotlin and Swift, so all keep what leaves the phone the same way, and
// the modules as Kotlin and Swift, so the apps turn on and off what the spec defines.

export const sqlFile = fileURLToPath(new URL('../../apps/api/schema.sql', import.meta.url))

export const kotlinFile = fileURLToPath(
  new URL(
    '../../apps/android/shared/src/commonMain/kotlin/cc/hyoban/moments/data/copy/CopyTables.kt',
    import.meta.url,
  ),
)

export const modulesFile = fileURLToPath(
  new URL(
    '../../apps/android/shared/src/commonMain/kotlin/cc/hyoban/moments/data/spec/Modules.kt',
    import.meta.url,
  ),
)

export const swiftFile = fileURLToPath(
  new URL('../../apps/apple/Sources/HereKit/Generated/CopyTables.swift', import.meta.url),
)

export const swiftModulesFile = fileURLToPath(
  new URL('../../apps/apple/Sources/HereKit/Generated/Modules.swift', import.meta.url),
)

export function renderSql() {
  return [
    '-- Generated from packages/data-spec/src/tables.ts by `vp run data:tables`. Do not edit by hand.',
    '-- The tables D1 keeps what leaves the phone in, as the app keeps its copy.',
    '',
    ...getTableStatements().map((statement) => `${statement};\n`),
  ].join('\n')
}

export function renderKotlin() {
  const raw = (text) => `"""\n${text.replaceAll('$', () => "${'$'}")}\n"""`
  const indent = (text, by) => text.replaceAll(/^(?=.)/gm, ' '.repeat(by))

  return [
    '// Generated from packages/data-spec/src/tables.ts by `vp run data:tables`. Do not edit by hand.',
    '',
    'package cc.hyoban.moments.data.copy',
    '',
    "/** The tables the copy keeps the API's records in, as D1 keeps them. */",
    'object CopyTables {',
    '    /** Every table, in the order `GET /v2/rows` hands them out. */',
    `    val names = listOf(${copiedTables.map((table) => JSON.stringify(table.name)).join(', ')})`,
    '',
    '    /** The statements that make them, one per table and index. */',
    '    val statements = listOf(',
    ...getTableStatements(copiedTables).map(
      (statement) => `${indent(raw(statement), 8)}.trimIndent(),`,
    ),
    '    )',
    '}',
    '',
  ].join('\n')
}

export function renderModules() {
  const list = (values) =>
    values.length === 0
      ? 'emptyList()'
      : `listOf(${values.map((value) => JSON.stringify(value)).join(', ')})`

  return [
    '// Generated from packages/data-spec/src/entry.ts by `vp run data:tables`. Do not edit by hand.',
    '',
    'package cc.hyoban.moments.data.spec',
    '',
    '/**',
    ' * The modules the spec defines (`modules` in `packages/data-spec/src/entry.ts`), in its order:',
    ' * what the items page lets the owner turn on or off as one, each owning the plugins whose',
    " * parts are its and the tables past the entries that are its. Off, a module's rows, pages",
    ' * and records are put away and its background work stops; its data stays on the phone for',
    ' * when it is turned on again.',
    ' */',
    'enum class Module(val key: String, val plugins: List<String>, val tables: List<String>) {',
    ...modules.map(
      (module, index) =>
        `    ${module.id.toUpperCase()}(${JSON.stringify(module.id)}, ${list(module.plugins)}, ${list(module.tables)})${index === modules.length - 1 ? ';' : ','}`,
    ),
    '',
    '    companion object {',
    '        fun of(key: String): Module? = entries.firstOrNull { it.key == key }',
    '    }',
    '}',
    '',
  ].join('\n')
}

/** A spec id as a Swift name: `check-ins` → `checkIns`. */
const swiftName = (id) => id.replace(/[-_]([a-z0-9])/g, (_, c) => c.toUpperCase())

export function renderSwift() {
  // A raw string, so neither a backslash nor an interpolation in a statement means anything.
  const raw = (text) => `        #"""\n${text.replaceAll(/^(?=.)/gm, ' '.repeat(8))}\n        """#`
  const statements = getTableStatements(copiedTables)

  return [
    '// Generated from packages/data-spec/src/tables.ts by `vp run data:tables`. Do not edit by hand.',
    '',
    "/// The tables the copy keeps the API's records in, as D1 keeps them.",
    'public enum CopyTables {',
    '    /// Every table, in the order `GET /v2/rows` hands them out.',
    `    public static let names = [${copiedTables.map((table) => JSON.stringify(table.name)).join(', ')}]`,
    '',
    '    /// The statements that make them, one per table and index.',
    '    public static let statements = [',
    ...statements.map(
      (statement, index) => `${raw(statement)}${index === statements.length - 1 ? '' : ','}`,
    ),
    '    ]',
    '}',
    '',
  ].join('\n')
}

export function renderSwiftModules() {
  const list = (values) => `[${values.map((value) => JSON.stringify(value)).join(', ')}]`
  const cases = (field) =>
    modules.map((module) => `        case .${swiftName(module.id)}: ${list(module[field])}`)

  return [
    '// Generated from packages/data-spec/src/entry.ts by `vp run data:tables`. Do not edit by hand.',
    '',
    '/// The modules the spec defines (`modules` in `packages/data-spec/src/entry.ts`), in its order:',
    '/// what the items page lets the owner turn on or off as one, each owning the plugins whose',
    "/// parts are its and the tables past the entries that are its. Off, a module's rows, pages",
    '/// and records are put away and its background work stops; its data stays on the device for',
    '/// when it is turned on again. `Module(rawValue:)` finds one by its key.',
    'public enum Module: String, CaseIterable, Sendable {',
    ...modules.map((module) => `    case ${swiftName(module.id)} = ${JSON.stringify(module.id)}`),
    '',
    "    /// The plugins whose parts are this module's.",
    '    public var plugins: [String] {',
    '        switch self {',
    ...cases('plugins'),
    '        }',
    '    }',
    '',
    "    /// The tables past the entries that are this module's.",
    '    public var tables: [String] {',
    '        switch self {',
    ...cases('tables'),
    '        }',
    '    }',
    '}',
    '',
  ].join('\n')
}
