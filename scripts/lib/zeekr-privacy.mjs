/** Publish only known statistical fields. Original captures must stay outside Git. */
const labels = new Set([
  '%',
  'ACC/LCC',
  'NZP',
  'h',
  'kWh',
  'km',
  's',
  '交流',
  '充电次数',
  '充电记录',
  '全部',
  '出行次数',
  '动态电耗',
  '单次最远距离',
  '单次最高时长',
  '基础车辆总里程',
  '天',
  '平均动态百公里电耗',
  '平均百公里电耗',
  '总时长',
  '总电耗',
  '总里程',
  '智能泊车辅助平均时长',
  '智能泊车辅助次数',
  '本月',
  '本月数据',
  '查看本月更多数据',
  '次',
  '用户',
  '用车时长',
  '直流',
  '相较上月',
  '统计周期：',
  '统计数据详情',
  '能量统计',
  '自提车至今',
  '行程统计',
  '行驶统计',
  '行驶里程',
  '车辆总里程',
  '辅助驾驶泊车数据',
  '辅助驾驶统计',
  '辅助驾驶行车数据',
  '里程超越',
  '静态电耗',
  '，爱车已陪伴您',
  '* 数据准确性将受车端【位置服务】开关影响，',
  '*含动态及静态电耗',
  '*数据准确性将受车辆信号及位置服务开关影响，仅供参考',
  '*数据将由车辆上传，更新或有延迟',
  '*数据的准确性可能会被车辆权限开关以及网络影响，由于数据需等车辆上传，更新可能会有延迟',
  '*月度行程报告的数据最大支持查询两年内月份',
  '由于数据需等车辆上传，更新可能会有延迟',
  '最多仅记录2年内的充电数据',
])
const screenPatterns = [
  /^[+-]?\d+(?:\.\d+)?\s*(?:km|min|kWh|h|s|%)?(?:\n(?:\d{4}-)?\d{1,2}-\d{1,2})?$/,
  /^(?:\d{4}-)?\d{1,2}-\d{1,2}$/,
  /^\d{4}年\d{1,2}月(?: 用车报告|数据)?$/,
  /^\d{4}年\d{2}月\d{2}日 - \d{4}年\d{2}月\d{2}日 共\d+天$/,
  /^\d{2}月 充电\d+次 共[\d.]+kWh$/,
  /^\d{1,2}月\d{1,2}日 \d{2}:\d{2}$/,
  /^时长\s*(?:\d+(?:小时|分钟|秒)\s*)+$/,
  /^相较上月[+-]?[\d.]+%$/,
  /^已超越了\d+%的用户$/,
]

function screenText(texts) {
  return (texts ?? []).filter(
    (text) => labels.has(text) || screenPatterns.some((pattern) => pattern.test(text)),
  )
}
function pick(value, fields) {
  return Object.fromEntries(
    fields.filter((key) => value[key] !== undefined).map((key) => [key, value[key]]),
  )
}
const shareFields = [
  'year',
  'month',
  'yearMonth',
  'mileage',
  'mileageMonthlyPercent',
  'mileageRankings',
  'drivingTime',
  'drivingTimeMonthlyPercent',
  'journeyNum',
  'dailyMileageY',
  'dailyMileageX',
  'drivingTimeY',
  'drivingTimeX',
  'longestMileage',
  'longestDrivingTime',
  'electricityConsumed',
  'ecy',
  'ecx',
  'emissionReductions',
  'avgElectricityConsumed',
  'avgEcPercent',
  'normalChargeNum',
  'fastChargeNum',
  'lccNum',
  'lccMileage',
  'accNum',
  'accMileage',
  'dynamicEc',
  'dynamicEcPercent',
  'dynamicAvgEc',
  'dynamicAvgEcPercent',
  'dynamicEcY',
  'staticEcY',
  'style',
  'showZid',
  'fuelConsumptionY',
  'avgFuelConsumption',
  'totalFuelConsumption',
  'avgFcPercent',
  'totalFcPercent',
  'powerType',
  'avgDynamicFc',
  'totalDynamicFc',
  'ecmonthlyPercent',
]
function trips(entry) {
  const output = pick(entry, ['month'])
  if (entry.report) {
    output.report = pick(entry.report, [
      'title',
      'distance_km',
      'duration_h',
      'trips',
      'longest_trip_km',
      'longest_trip_h',
      'energy_kwh',
      'driving_energy_kwh',
      'consumption_kwh_per_100km',
      'charges',
      'distance_beats_percent',
      'vs_last_month',
    ])
    output.report.raw_text = screenText(entry.report.raw_text)
  }
  if (entry.daily)
    output.daily = entry.daily.map((day) =>
      pick(day, [
        'date',
        'distance_km',
        'duration_min',
        'energy_kwh',
        'energy_driving_kwh',
        'energy_parked_kwh',
      ]),
    )
  if (entry.share_data) output.share_data = pick(entry.share_data, shareFields)
  return output
}
function charging(entry) {
  return {
    ...pick(entry, ['month', 'count', 'kwh']),
    sessions: entry.sessions.map((session) =>
      pick(session, ['type', 'kwh', 'start', 'duration_min', 'duration_text']),
    ),
    raw_text: screenText(entry.raw_text),
  }
}

export function redactZeekrCapture(raw) {
  const result = { redacted: true, ...pick(raw, ['captured_at', 'month']) }
  if (raw.trips)
    result.trips = raw.trips.months ? { months: raw.trips.months.map(trips) } : trips(raw.trips)
  if (raw.charging)
    result.charging = raw.charging.months
      ? { months: raw.charging.months.map(charging) }
      : charging(raw.charging)
  if (raw.assisted_driving)
    result.assisted_driving = {
      periods: raw.assisted_driving.periods.map((period) => ({
        ...pick(period, [
          'period',
          'nzp_km',
          'nzp_km_percent',
          'acc_lcc_km',
          'acc_lcc_km_percent',
          'total_km',
          'total_h',
          'beats_percent',
          'nzp_h',
          'nzp_h_percent',
          'acc_lcc_h',
          'acc_lcc_h_percent',
          'parking_assists',
          'parking_assist_avg_s',
          'days_owned',
        ]),
        raw_text: screenText(period.raw_text),
      })),
    }
  if (raw.raw_screens)
    result.raw_screens = raw.raw_screens
      .filter((screen) => ['行程统计', '充电记录'].includes(screen.raw_text[0]))
      .map((screen) => ({ sequence: screen.sequence, raw_text: screenText(screen.raw_text) }))
  assertSafeValues(result)
  return result
}

// Fields copied as statistics must never carry free-form strings or access credentials.
function assertSafeValues(value, path = '') {
  if (Array.isArray(value))
    return value.forEach((item, i) => assertSafeValues(item, `${path}/${i}`))
  if (value && typeof value === 'object')
    return Object.entries(value).forEach(([key, item]) => assertSafeValues(item, `${path}/${key}`))
  if (typeof value !== 'string') return
  if (/https?:\/\/|\b1[3-9]\d{9}\b|\d{3}\*{3,}\d{2,}|drive\.[a-f0-9]+/i.test(value))
    throw new Error(`Sensitive text at ${path}`)
  const valid =
    screenPatterns.some((pattern) => pattern.test(value)) ||
    labels.has(value) ||
    /^\d{4}-\d{2}(?:-\d{2}(?:T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?)?)?$/.test(value) ||
    ['AC', 'DC', 'since_delivery'].includes(value) ||
    /^(?:\d+(?:小时|分钟|秒)\s*)+$/.test(value)
  if (!valid) throw new Error(`Unexpected non-statistical text at ${path}`)
}

export function assertPublicZeekrCapture(raw) {
  if (raw.redacted !== true) throw new Error('Only explicitly redacted sources can be published')
  const clean = redactZeekrCapture(raw)
  // Compare key sets and values without treating object key order as a privacy issue.
  const ordered = (value) =>
    Array.isArray(value)
      ? value.map(ordered)
      : value && typeof value === 'object'
        ? Object.fromEntries(
            Object.keys(value)
              .sort()
              .map((key) => [key, ordered(value[key])]),
          )
        : value
  if (JSON.stringify(ordered(raw)) !== JSON.stringify(ordered(clean)))
    throw new Error('Source includes non-public fields or screen text')
}
