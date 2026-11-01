// The tables what leaves the phone is kept in, the same in D1 and in the app's copy. A row
// is its key, the revision it last changed at, when it was deleted, and its record as the
// spec has it, in JSON; every other column is worked out from the record by SQLite itself,
// so it always says what the record says. `vp run data:tables` writes these out for both.

export interface TableColumn {
  name: string
  /** SQL over `record`, the row's JSON. */
  as: string
}

export interface Table {
  name: string
  /** What the key is: an entry's uid, a collection's key, a place's id… */
  key: string
  columns: readonly TableColumn[]
  /** Indexes beyond `(rev, key)`, which every table has for `GET /v2/rows`. */
  indexes: readonly (readonly string[])[]
}

const field = (path: string) => `json_extract(record, '$.${path}')`
const day = (path: string) => `substr(${field(path)}, 1, 10)`

/** The columns every row has, before those worked out from its record. */
export const ROW_COLUMNS = ['key', 'rev', 'deleted_at', 'record'] as const

/** Every table, in the order `GET /v2/rows` hands them out: what others name comes first. */
export const tables = [
  { name: 'places', key: 'a place id', columns: [], indexes: [] },
  { name: 'routes', key: 'a leg key', columns: [], indexes: [] },
  {
    name: 'collections',
    key: "the collection's slug, else its uid in lowercase (getCollectionKey)",
    columns: [
      { name: 'uid', as: field('uid') },
      { name: 'start_day', as: day('span.start') },
      { name: 'end_day', as: day('span.end') },
      { name: 'visibility', as: field('visibility') },
    ],
    indexes: [['start_day', 'key']],
  },
  {
    name: 'entries',
    key: "the entry's uid",
    columns: [
      { name: 'at', as: field('at') },
      { name: 'day', as: day('at') },
      // A Moment the first shape has (it has words), a mark (nothing but its moment and
      // point), or the rest, as isMomentEntry and isMarkEntry tell them apart.
      {
        name: 'kind',
        as: `CASE WHEN json_type(record, '$.parts.text') IS NOT NULL THEN 'moment' WHEN coalesce(${field('parts')}, '{}') = '{}' THEN 'mark' ELSE 'entry' END`,
      },
      { name: 'place', as: field('place') },
      { name: 'visibility', as: field('visibility') },
      { name: 'published_at', as: field('published.at') },
    ],
    indexes: [['day', 'key']],
  },
  {
    name: 'tracks',
    key: "the public track's uid",
    columns: [
      { name: 'start_at', as: field('start') },
      { name: 'end_at', as: field('end') },
      { name: 'collection', as: field('collection') },
    ],
    indexes: [],
  },
  {
    name: 'vehicle_months',
    key: '`<device>/<YYYY-MM>`',
    columns: [
      { name: 'device_id', as: field('deviceId') },
      { name: 'month', as: field('month') },
    ],
    indexes: [],
  },
  {
    name: 'vehicle_assisted',
    key: '`<device>/<captured at>`',
    columns: [
      { name: 'device_id', as: field('deviceId') },
      { name: 'captured_at', as: field('capturedAt') },
    ],
    indexes: [],
  },
  { name: 'vehicle_captures', key: "the capture's SHA-256", columns: [], indexes: [] },
] as const satisfies readonly Table[]

export type TableName = (typeof tables)[number]['name']

/** The statements that make the tables, one per table and index. */
export function getTableStatements(): string[] {
  return tables.flatMap((table: Table) => [
    [
      `CREATE TABLE ${table.name} (`,
      [
        '  key TEXT PRIMARY KEY',
        '  rev INTEGER NOT NULL',
        '  deleted_at TEXT',
        '  record TEXT NOT NULL',
        ...table.columns.map(
          (column) => `  ${column.name} GENERATED ALWAYS AS (${column.as}) VIRTUAL`,
        ),
      ].join(',\n'),
      ')',
    ].join('\n'),
    ...[['rev', 'key'], ...table.indexes].map(
      (columns) =>
        `CREATE INDEX ${table.name}_${columns[0]} ON ${table.name} (${columns.join(', ')})`,
    ),
  ])
}
