import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'vite-plus/test'

import { vehicleExpensesSchema, vehicleMonthSchema } from '@hyoban/data-spec'
import { canonicalJson, convertZeekrCaptures, hashBytes, scale } from './zeekr-data.mjs'

const directory = new URL('../../data/zeekr/', import.meta.url)
const inputs = JSON.parse(readFileSync(new URL('imports.json', directory), 'utf8')).map(
  (entry) => ({
    ...entry,
    bytes: readFileSync(new URL(entry.file, directory)),
  }),
)
const converted = convertZeekrCaptures(inputs)
const month = (key, result = converted) => result.files.get(`months/zeekr-007gt/${key}.json`)

function revised(input, change) {
  const raw = JSON.parse(input.bytes.toString())
  change(raw)
  const bytes = Buffer.from(canonicalJson(raw))
  return { ...input, file: `revision-${input.file}`, bytes, sha256: hashBytes(bytes) }
}

test('converts every existing record and reproduces the saved model without changing raw bytes', () => {
  assert.deepEqual(converted.validation.counts, {
    captures: 2,
    months: 15,
    days: 264,
    chargingSessions: 94,
    monthlyAssistance: 3,
    cumulativeAssistance: 1,
  })
  assert.ok(converted.validation.checks.every((check) => check.passed))
  for (const [file, record] of converted.files) {
    assert.equal(readFileSync(new URL(file, directory), 'utf8'), canonicalJson(record), file)
  }
  for (const input of inputs)
    assert.equal(hashBytes(readFileSync(new URL(input.file, directory))), input.sha256)
})

test('preserves mixed historical energy and missing splits instead of manufacturing zeroes', () => {
  const legacy = month('2025-11').days.find((row) => row.date === '2025-11-06')
  assert.equal(legacy.energyWh, 24800)
  assert.equal(legacy.distanceMeters, 181000)
  assert.ok(!('drivingEnergyWh' in legacy))
  assert.ok(!('parkedEnergyWh' in legacy))
  assert.equal(
    month('2025-11').days.reduce((n, row) => n + row.energyWh, 0),
    542900,
  )
  assert.equal(month('2025-08').report.reportedConsumption.basis, 'driving_and_parked')
  assert.equal(month('2026-09').report.reportedConsumption.basis, 'driving')
  assert.ok(!('charging' in month('2025-08')))
})

test('current month retains actual chart dates and separate charging totals', () => {
  const record = month('2026-10')
  assert.equal(record.report.distanceMeters, 3516000)
  assert.equal(record.report.energyWh, 593200)
  assert.equal(record.charging.reported.energyWh, 634600)
  assert.equal(
    record.days.reduce((n, row) => n + row.durationSeconds, 0),
    169260,
  )
  assert.equal(
    record.days.reduce((n, row) => n + row.parkedEnergyWh, 0),
    37800,
  )
  assert.equal(record.days.length, 11)
  assert.ok(!record.days.some((row) => row.date === '2026-10-08'))
  assert.ok(!('durationSeconds' in record.report))
  assert.ok(!('assistedDriving' in record))
  assert.equal(record.charging.sessions[0].startedAt, '2026-10-07T08:00+08:00')
  assert.equal(record.charging.sessions[0].durationSeconds, 25200)
  for (const key of ['status', 'periodState', 'batteryPercent', 'location', 'rangeKm'])
    assert.ok(!(key in record))
})

test('keeps duplicate charging rows with distinct evidence and does not append on reimport', () => {
  const duplicate = month('2026-04').charging.sessions.filter(
    (row) => row.startedAt === '2026-04-12T08:36+08:00',
  )
  assert.equal(duplicate.length, 2)
  assert.equal(duplicate[0].energyWh, 22600)
  assert.equal(duplicate[1].energyWh, 22600)
  assert.notEqual(duplicate[0].source.pointer, duplicate[1].source.pointer)
  const repeated = convertZeekrCaptures([...inputs, ...inputs])
  assert.deepEqual([...repeated.files], [...converted.files])
})

test('latest report replaces only its own section, retaining older daily and charging sources', () => {
  const next = revised(inputs[1], (raw) => {
    raw.captured_at = '2026-10-25T08:00:00+08:00'
    delete raw.trips.daily
    delete raw.charging
  })
  const result = convertZeekrCaptures([next, ...inputs])
  const record = month('2026-10', result)
  assert.equal(record.capturedAt, '2026-10-25T08:00:00+08:00')
  assert.deepEqual(record.days, month('2026-10').days)
  assert.deepEqual(record.charging, month('2026-10').charging)
  assert.equal(
    record.sources.find((source) => source.pointer === '/trips/report').captureId,
    next.sha256,
  )
  assert.equal(result.validation.counts.chargingSessions, 94)
})

test('records original energy discrepancies without changing either side', () => {
  const december = month('2025-12')
  assert.equal(december.report.energyWh, 212300)
  assert.equal(
    december.days.reduce((n, row) => n + row.energyWh, 0),
    212600,
  )
  const differences = converted.validation.warnings.filter(
    (warning) => warning.differenceWh !== undefined,
  )
  assert.deepEqual(
    differences.map((warning) => warning.differenceWh),
    [300, 200, 400],
  )
})

test('rejects changed raw bytes and detects parsed daily values that disagree with raw share arrays', () => {
  assert.throws(
    () => convertZeekrCaptures([{ ...inputs[0], bytes: Buffer.from('{}') }]),
    /SHA-256 mismatch/,
  )
  const bad = revised(inputs[0], (raw) => {
    raw.trips.months[0].daily[0].energy_kwh += 1
  })
  const result = convertZeekrCaptures([bad])
  assert.ok(
    result.validation.checks.some((check) => check.name === 'energy_source_Wh' && !check.passed),
  )
})

test('requires tooltip evidence for clicked daily data', () => {
  const bad = revised(inputs[1], (raw) => {
    raw.raw_screens = []
  })
  const result = convertZeekrCaptures([bad])
  assert.ok(
    result.validation.checks.some((check) => check.name === 'energy_tooltip' && !check.passed),
  )
})

test('schemas reject invalid dates, duplicate dates and charging sessions outside the month', () => {
  const record = structuredClone(month('2026-10'))
  record.days.push(record.days[0])
  assert.equal(vehicleMonthSchema.safeParse(record).success, false)
  record.days.pop()
  record.days[0].date = '2026-02-30'
  assert.equal(vehicleMonthSchema.safeParse(record).success, false)
  record.days = []
  record.charging.sessions[0].startedAt = '2026-09-30T08:00+08:00'
  assert.equal(vehicleMonthSchema.safeParse(record).success, false)
})

test('unit conversions preserve precision and reject invalid source quantities', () => {
  assert.equal(scale(0.1, 1000), 100)
  assert.equal(scale(4.6, 3600), 16560)
  assert.throws(() => scale(0.0001, 1000), /cannot be represented/)
  assert.throws(() => scale(-1, 1000), /Invalid quantity/)
})

test('the expense ledger parses, oldest first, refunding no more than was paid', () => {
  const raw = JSON.parse(readFileSync(new URL('expenses/zeekr-007gt.json', directory), 'utf8'))
  const { entries } = vehicleExpensesSchema.parse(raw)
  const fen = (yuan) => Math.round(yuan * 100)
  assert.equal(entries.length, 338)
  assert.ok(entries.every((entry, i) => i === 0 || entries[i - 1].at <= entry.at))
  for (const { amount, at, refunds = [] } of entries) {
    assert.ok(refunds.reduce((total, refund) => total + fen(refund.amount), 0) <= fen(amount))
    assert.ok(refunds.every((refund) => refund.at >= at))
  }
  // The schema lets unknown fields through; this ledger keeps only these, so accounts,
  // tags and discounts can't come back with a re-import.
  assert.deepEqual(Object.keys(raw).sort(), ['deviceId', 'entries', 'source'])
  const allowed = new Set(['at', 'amount', 'category', 'subcategory', 'note', 'refunds'])
  for (const entry of raw.entries) {
    assert.deepEqual(
      Object.keys(entry).filter((key) => !allowed.has(key)),
      [],
      `unexpected fields at ${entry.at}`,
    )
    for (const refund of entry.refunds ?? []) {
      assert.deepEqual(Object.keys(refund).sort(), ['amount', 'at'])
    }
  }
  // Where the car was stays out of the public ledger, as it does from the app's records:
  // parking, toll and charging notes name car parks, stations and chargers.
  assert.ok(
    entries.every(
      (entry) => !entry.note || !['parking', 'toll', 'charging'].includes(entry.category),
    ),
  )
  // Parking notes can carry the car's plate; it stays out of the public ledger.
  assert.ok(entries.every((entry) => !/[\u4e00-\u9fa5][A-Z][A-Z0-9]{5,6}/.test(entry.note ?? '')))
})
