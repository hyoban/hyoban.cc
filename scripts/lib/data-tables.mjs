import { fileURLToPath } from 'node:url'

import { copiedTables, getTableStatements } from '@hyoban/data-spec/tables'

// What `vp run data:tables` writes from @hyoban/data-spec/tables: the tables as SQL for D1,
// and those the apps copy as Kotlin and Swift, so all keep what leaves the phone the same way.

export const sqlFile = fileURLToPath(new URL('../../apps/api/schema.sql', import.meta.url))

export const kotlinFile = fileURLToPath(
  new URL(
    '../../apps/android/app/src/main/kotlin/cc/hyoban/moments/data/copy/CopyTables.kt',
    import.meta.url,
  ),
)

export const swiftFile = fileURLToPath(
  new URL('../../apps/ios/Here/Data/CopyTables.swift', import.meta.url),
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

export function renderSwift() {
  const raw = (text) => `"""\n${text.replaceAll(/^/gm, '        ')}\n        """`

  return [
    '// Generated from packages/data-spec/src/tables.ts by `vp run data:tables`. Do not edit by hand.',
    '',
    "/// The tables the copy keeps the API's records in, as D1 keeps them.",
    'enum CopyTables {',
    '    /// Every table, in the order `GET /v2/rows` hands them out.',
    `    static let names = [${copiedTables.map((table) => JSON.stringify(table.name)).join(', ')}]`,
    '',
    '    /// The statements that make them, one per table and index.',
    '    static let statements = [',
    ...getTableStatements(copiedTables).map((statement) => `        ${raw(statement)},`),
    '    ]',
    '}',
    '',
  ].join('\n')
}
