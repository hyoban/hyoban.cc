import { createHash } from 'node:crypto'

import {
  vehicleAssistedDrivingCaptureSchema,
  vehicleCaptureSchema,
  vehicleMonthSchema,
} from '@hyoban/data-spec'

export const canonicalJson = (value) => `${JSON.stringify(value, null, 2)}\n`
export const hashBytes = (bytes) => createHash('sha256').update(bytes).digest('hex')

/** Unit conversion must not silently round a source value. */
export function scale(value, factor) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`Invalid quantity: ${value}`)
  }
  const scaled = value * factor
  const integer = Math.round(scaled)
  if (!Number.isSafeInteger(integer) || Math.abs(integer - scaled) > 1e-6) {
    throw new Error(`Quantity ${value} cannot be represented in the target unit`)
  }
  return integer
}

export function withOffset(value, offset) {
  if (typeof value !== 'string') throw new Error('Missing source timestamp')
  const result = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : value + offset
  if (!Number.isFinite(Date.parse(result))) throw new Error(`Invalid timestamp: ${result}`)
  return result
}

function mapFields(input, fields) {
  return Object.fromEntries(
    fields
      .filter(([, from]) => input[from] !== undefined)
      .map(([to, from, factor = 1]) => [to, scale(input[from], factor)]),
  )
}

const energyFields = [
  ['energyWh', 'energy_kwh', 1000],
  ['drivingEnergyWh', 'energy_driving_kwh', 1000],
  ['parkedEnergyWh', 'energy_parked_kwh', 1000],
]

function reportFromScreen(report) {
  const value = mapFields(report, [
    ['distanceMeters', 'distance_km', 1000],
    ['durationSeconds', 'duration_h', 3600],
    ['tripCount', 'trips'],
    ['longestTripMeters', 'longest_trip_km', 1000],
    ['longestTripDurationSeconds', 'longest_trip_h', 3600],
    ['energyWh', 'energy_kwh', 1000],
    ['drivingEnergyWh', 'driving_energy_kwh', 1000],
    ['chargeCount', 'charges'],
  ])
  if (report.distance_beats_percent !== undefined)
    value.distanceBeatsPercent = report.distance_beats_percent
  if (report.consumption_kwh_per_100km !== undefined) {
    const labels = report.raw_text ?? []
    const driving = labels.some((text) => text.includes('动态百公里电耗'))
    const combined = labels.some((text) => text.includes('含动态及静态电耗'))
    if (!driving && !combined) throw new Error('Unknown reported consumption basis')
    value.reportedConsumption = {
      whPer100Km: scale(report.consumption_kwh_per_100km, 1000),
      basis: combined ? 'driving_and_parked' : 'driving',
    }
  }
  return value
}

function assistance(input) {
  const value = mapFields(input, [
    ['distanceMeters', 'total_km', 1000],
    ['durationSeconds', 'total_h', 3600],
    ['parkingCount', 'parking_assists'],
    ['parkingAverageSeconds', 'parking_assist_avg_s'],
    ['daysOwned', 'days_owned'],
  ])
  if (input.beats_percent !== undefined) value.beatsPercent = input.beats_percent
  for (const [name, prefix] of [
    ['nzp', 'nzp'],
    ['accLcc', 'acc_lcc'],
  ]) {
    value[name] = mapFields(input, [
      ['distanceMeters', `${prefix}_km`, 1000],
      ['durationSeconds', `${prefix}_h`, 3600],
    ])
    for (const [field, suffix] of [
      ['reportedDistancePercent', 'km_percent'],
      ['reportedDurationPercent', 'h_percent'],
    ]) {
      if (input[`${prefix}_${suffix}`] !== undefined)
        value[name][field] = input[`${prefix}_${suffix}`]
    }
  }
  return value
}

export function resolvePointer(value, pointer) {
  return pointer
    .split('/')
    .slice(1)
    .reduce((node, token) => node?.[token.replaceAll('~1', '/').replaceAll('~0', '~')], value)
}

/** Convert explicitly registered captures. Latest observations replace a whole section, never append. */
export function convertZeekrCaptures(inputs) {
  const files = new Map(),
    months = new Map(),
    captures = new Map(),
    cumulative = []
  const checks = [],
    warnings = []
  function check(scope, name, actual, expected, tolerance = 0) {
    const passed =
      typeof actual === 'number' && typeof expected === 'number'
        ? Math.abs(actual - expected) <= tolerance
        : JSON.stringify(actual) === JSON.stringify(expected)
    checks.push({ scope, name, actual, expected, ...(tolerance ? { tolerance } : {}), passed })
  }
  function warning(scope, message, details = {}) {
    warnings.push({ scope, message, ...details })
  }
  const prepared = inputs
    .map((input) => {
      const id = hashBytes(input.bytes)
      if (id !== input.sha256) throw new Error(`Raw SHA-256 mismatch: ${input.file}`)
      const raw = JSON.parse(input.bytes.toString())
      const capture = vehicleCaptureSchema.parse({
        id,
        deviceId: input.deviceId,
        capturedAt: withOffset(raw.captured_at, input.offset),
        timezone: input.timezone,
        raw: { file: input.file, sha256: id, redacted: raw.redacted === true },
      })
      return { ...input, raw, capture }
    })
    .sort(
      (a, b) =>
        Date.parse(a.capture.capturedAt) - Date.parse(b.capture.capturedAt) ||
        a.capture.id.localeCompare(b.capture.id),
    )

  for (const { raw, capture, offset } of prepared) {
    if (captures.has(capture.id)) {
      if (captures.get(capture.id).capture.deviceId !== capture.deviceId)
        throw new Error('Capture assigned to multiple devices')
      continue
    }
    captures.set(capture.id, { capture, raw })
    files.set(`captures/${capture.id}.json`, capture)
    const ref = (pointer, method = 'screen') => ({ captureId: capture.id, pointer, method })
    const update = (month, section, data, sources) => {
      const key = `${capture.deviceId}/${month}`
      let entry = months.get(key)
      if (!entry) {
        entry = {
          record: { deviceId: capture.deviceId, month, capturedAt: capture.capturedAt },
          sources: {},
        }
        months.set(key, entry)
      }
      entry.record.capturedAt = capture.capturedAt
      entry.record[section] = data
      entry.sources[section] = sources
    }
    const tripEntries = raw.trips?.months
      ? raw.trips.months.map((entry, i) => ({ entry, pointer: `/trips/months/${i}` }))
      : raw.month && raw.trips
        ? [{ entry: { ...raw.trips, month: raw.month }, pointer: '/trips' }]
        : []
    for (const { entry, pointer } of tripEntries) {
      const scope = `${capture.id}/${entry.month}`
      if (entry.report)
        update(entry.month, 'report', reportFromScreen(entry.report), [ref(`${pointer}/report`)])
      if (entry.daily) {
        const method = entry.share_data ? 'share_report' : 'screen_tap'
        const days = entry.daily.map((day, i) => ({
          date: day.date,
          ...mapFields(day, [
            ['distanceMeters', 'distance_km', 1000],
            ['durationSeconds', 'duration_min', 60],
            ...energyFields,
          ]),
          source: ref(`${pointer}/daily/${i}`, method),
        }))
        const sources = [ref(`${pointer}/daily`, method)]
        if (entry.share_data) {
          sources.push(ref(`${pointer}/share_data`, 'share_report'))
          checkShareDays(entry, scope, check)
        } else {
          checkTappedDays(entry.daily, raw.raw_screens ?? [], scope, check)
        }
        update(entry.month, 'days', days, sources)
      }
    }
    const chargeEntries = raw.charging?.months
      ? raw.charging.months.map((entry, i) => ({ entry, pointer: `/charging/months/${i}` }))
      : raw.charging?.month
        ? [{ entry: raw.charging, pointer: '/charging' }]
        : []
    for (const { entry, pointer } of chargeEntries) {
      update(
        entry.month,
        'charging',
        {
          reported: { count: entry.count, energyWh: scale(entry.kwh, 1000) },
          sessions: entry.sessions.map((session, i) => ({
            startedAt: withOffset(session.start, offset),
            type: session.type,
            energyWh: scale(session.kwh, 1000),
            durationSeconds: scale(session.duration_min, 60),
            source: ref(`${pointer}/sessions/${i}`),
          })),
        },
        [ref(pointer)],
      )
    }
    for (const [i, period] of (raw.assisted_driving?.periods ?? []).entries()) {
      const source = ref(`/assisted_driving/periods/${i}`)
      if (period.period === 'since_delivery') {
        const record = vehicleAssistedDrivingCaptureSchema.parse({
          deviceId: capture.deviceId,
          capturedAt: capture.capturedAt,
          statistics: assistance(period),
          source,
        })
        files.set(`assisted-driving/${capture.deviceId}/${capture.id}.json`, record)
        cumulative.push(record)
      } else update(period.period, 'assistedDriving', assistance(period), [source])
    }
    if (!tripEntries.length && !chargeEntries.length && !raw.assisted_driving?.periods?.length) {
      throw new Error(`No supported vehicle data in ${capture.raw.file}`)
    }
  }
  const sum = (rows, field) => rows.reduce((value, row) => value + (row[field] ?? 0), 0)
  for (const [key, entry] of [...months.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const record = vehicleMonthSchema.parse({
      ...entry.record,
      sources: Object.values(entry.sources).flat(),
    })
    const rows = record.days ?? [],
      report = record.report ?? {}
    for (const [field, tolerance] of [
      ['distanceMeters', 0],
      ['energyWh', 100],
      ['drivingEnergyWh', 100],
      ['durationSeconds', 180],
    ]) {
      if (!rows.length || report[field] === undefined) continue
      if (!rows.every((row) => row[field] !== undefined)) {
        warning(key, `Some daily ${field} values are absent; no zero was substituted.`)
        continue
      }
      const dailyTotal = sum(rows, field),
        reported = report[field]
      if (field === 'energyWh' && Math.abs(dailyTotal - reported) > tolerance) {
        warning(key, 'Source daily energy differs from the monthly report.', {
          dailyWh: dailyTotal,
          reportedWh: reported,
          differenceWh: dailyTotal - reported,
        })
      } else check(key, `daily_${field}_vs_report`, dailyTotal, reported, tolerance)
    }
    if (record.charging) {
      const { reported, sessions } = record.charging
      check(key, 'charging_count', sessions.length, reported.count)
      check(key, 'charging_energyWh', sum(sessions, 'energyWh'), reported.energyWh)
      const seen = new Map()
      for (const session of sessions) {
        const signature = JSON.stringify([
          session.startedAt,
          session.type,
          session.energyWh,
          session.durationSeconds,
        ])
        if (seen.has(signature))
          warning(key, 'Identical source charging rows retained.', {
            first: seen.get(signature),
            duplicate: session.source,
          })
        else seen.set(signature, session.source)
      }
      if (report.chargeCount !== undefined && report.chargeCount !== reported.count) {
        warning(key, 'Trip report and charging page have different counts.', {
          tripReport: report.chargeCount,
          chargingPage: reported.count,
        })
      }
    }
    for (const source of [
      ...record.sources,
      ...rows.map((row) => row.source),
      ...(record.charging?.sessions ?? []).map((row) => row.source),
    ]) {
      check(
        key,
        `source:${source.captureId}:${source.pointer}`,
        resolvePointer(captures.get(source.captureId)?.raw, source.pointer) !== undefined,
        true,
      )
    }
    files.set(`months/${key}.json`, record)
  }
  const records = [...months.values()].map((entry) => entry.record)
  const validation = {
    captures: [...captures.values()].map(({ capture }) => ({
      id: capture.id,
      file: capture.raw.file,
      sha256: capture.raw.sha256,
    })),
    counts: {
      captures: captures.size,
      months: months.size,
      days: records.reduce((n, record) => n + (record.days?.length ?? 0), 0),
      chargingSessions: records.reduce(
        (n, record) => n + (record.charging?.sessions.length ?? 0),
        0,
      ),
      monthlyAssistance: records.filter((record) => record.assistedDriving).length,
      cumulativeAssistance: cumulative.length,
    },
    checks,
    warnings,
    omissions: {
      chargingMonths: records
        .filter((record) => !record.charging)
        .map((record) => `${record.deviceId}/${record.month}`)
        .sort(),
      assistedDrivingMonths: records
        .filter((record) => !record.assistedDriving)
        .map((record) => `${record.deviceId}/${record.month}`)
        .sort(),
    },
  }
  return { files, validation }
}

function checkShareDays(entry, scope, check) {
  const data = entry.share_data,
    rows = entry.daily
  check(scope, 'share_month', data.yearMonth, entry.month)
  const days = data.dailyMileageX ?? []
  check(
    scope,
    'daily_dates_vs_share',
    rows.map((row) => row.date),
    days.map((day) => `${entry.month}-${String(day).padStart(2, '0')}`),
  )
  check(scope, 'duration_dates_vs_share', data.drivingTimeX, days)
  check(scope, 'energy_dates_vs_share', data.ecx, days)
  for (const [field, source, tolerance] of [
    ['distance_km', 'mileage', 0],
    ['energy_kwh', 'electricityConsumed', 0],
    ['driving_energy_kwh', 'dynamicEc', 0],
    ['trips', 'journeyNum', 0],
    ['longest_trip_km', 'longestMileage', 0],
  ]) {
    if (entry.report?.[field] !== undefined)
      check(scope, `report_${field}_vs_share`, entry.report[field], data[source], tolerance)
  }
  if (entry.report?.duration_h !== undefined)
    check(
      scope,
      'report_duration_vs_share_seconds',
      scale(entry.report.duration_h, 3600),
      scale(data.drivingTime, 60),
      180,
    )
  if (entry.report?.longest_trip_h !== undefined)
    check(
      scope,
      'report_longest_duration_vs_share_seconds',
      scale(entry.report.longest_trip_h, 3600),
      scale(data.longestDrivingTime, 60),
      180,
    )
  if (entry.report?.charges !== undefined)
    check(
      scope,
      'report_charges_vs_share',
      entry.report.charges,
      data.normalChargeNum + data.fastChargeNum,
    )
  const dynamic = data.dynamicEcY ?? [],
    parked = data.staticEcY ?? [],
    legacy = data.ecy ?? []
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i],
      key = `${scope}/${row.date}`
    check(key, 'distance_source', row.distance_km, data.dailyMileageY?.[i])
    check(key, 'duration_source', row.duration_min, data.drivingTimeY?.[i])
    const split =
      dynamic[i] !== undefined &&
      parked[i] !== undefined &&
      (dynamic[i] + parked[i] !== 0 || !legacy[i])
    const expectedWh = split
      ? scale(dynamic[i], 1000) + scale(parked[i], 1000)
      : scale(legacy[i], 1000)
    check(key, 'energy_source_Wh', scale(row.energy_kwh, 1000), expectedWh)
    check(key, 'dynamic_source', row.energy_driving_kwh, split ? dynamic[i] : undefined)
    check(key, 'parked_source', row.energy_parked_kwh, split ? parked[i] : undefined)
  }
}

function checkTappedDays(rows, screens, scope, check) {
  for (const row of rows) {
    const [, month, day] = row.date.split('-').map(Number),
      token = `${month}-${day}`
    for (const [key, unit] of [
      ['distance_km', 'km'],
      ['duration_min', 'min'],
    ]) {
      check(
        `${scope}/${row.date}`,
        `${key}_tooltip`,
        screens.some((screen) => screen.raw_text.includes(`${row[key]}${unit}\n${token}`)),
        true,
      )
    }
    const energyMatches = screens.some(({ raw_text: texts }) => {
      let i = texts.indexOf(token) + 1
      if (!i) return false
      const values = {}
      while (
        ['静态电耗', '动态电耗'].includes(texts[i]) &&
        /^\d+(?:\.\d+)?kWh$/.test(texts[i + 1] ?? '')
      ) {
        values[texts[i]] = Number(texts[i + 1].slice(0, -3))
        i += 2
      }
      return (
        Object.keys(values).length > 0 &&
        (values['静态电耗'] ?? 0) === row.energy_parked_kwh &&
        (values['动态电耗'] ?? 0) === row.energy_driving_kwh
      )
    })
    check(`${scope}/${row.date}`, 'energy_tooltip', energyMatches, true)
    check(
      `${scope}/${row.date}`,
      'energy_split',
      scale(row.energy_kwh, 1000),
      scale(row.energy_driving_kwh, 1000) + scale(row.energy_parked_kwh, 1000),
    )
  }
}
