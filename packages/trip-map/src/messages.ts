// Every word the map and the player show, as templates with `{name}` slots, so a site
// can replace them for another language with `messages` in its options. The defaults
// are the Chinese the package was written in.

export const zhCN = {
  /** The BCP 47 tag the dates and numbers are formatted in. */
  locale: 'zh-CN',

  // The map page
  clusterLabel: '{title}，共 {count} 条记录',
  clusterName: '{name}等',
  clusterNamedTitle: '{name} {count} 个地点',
  clusterPlaces: '{count} 处',
  clusterTitle: '{count} 个地点',
  drawerFailed: '没能加载这个地点的记录。',
  drawerLoading: '加载中…',
  fogLit: '点亮 {cities} 座城市 · {provinces} 个省份',
  mapInitFailed: '浏览器无法初始化地图。',
  mapLoadFailed: '地图资源加载失败。',
  mapLoadTimeout: '地图加载超时。',
  mapLoadingSlow: '地图加载得有点慢，再等等…',
  mapNoPlaces: '还没有带地点的记录。',
  mapNoToken: '尚未配置 Mapbox Token。',
  momentCount: '{count} 条记录',
  placeEyebrow: '{count} 条记录 · {years}',
  placeLabel: '{name}，{count} 条记录，首次记录于 {year} 年',
  screenshotRendering: '正在生成截图…',
  statDriving: '自驾 {count} 公里',
  statMoments: '{count} 条记录',
  statPlaces: '{count} 个地点',
  statTrips: '{count} 段旅程',
  tapAgainToPlay: '再点一次回放',
  yearItem: '{year} 年',
  yearSeparator: '、',

  // The trip player's cards
  dayLabel: '第 {day} 天',
  intro: '开头',
  placeSeparator: '、',
  playbackSpeed: '播放速度 {speed}×',
  replay: '重新播放',
  separator: ' · ',
  summary: '旅程回顾',
  tripMeta: '{range} · {days} 天 · {moments} 条记录',
  unitDays: '天',
  unitDrivingHours: '小时车程',
  unitDrivingKm: '公里自驾',
  unitMoments: '条记录',
  unitPhotos: '张照片',
  unitPlaces: '个地点',
  videoTitle: '视频',
  viewDayLink: '查看当天记录',
  viewDayMoments: '查看 {date}的记录',
  viewPhoto: '查看第 {index} 张照片',

  // The trip player's controls
  cancel: '取消',
  chaptersFile: '{name}-章节.txt',
  close: '关闭',
  closePlayback: '退出旅程回放',
  exitPresentation: '退出放映',
  exportVideo: '导出视频',
  landscape: '横屏 16:9',
  landscapeFile: '横屏',
  music: '背景音乐',
  next: '下一站',
  noMusic: '无音乐',
  pause: '暂停',
  play: '播放',
  portrait: '竖屏 9:16',
  portraitFile: '竖屏',
  present: '放映',
  presentStart: '开始放映',
  previous: '上一站',
  progress: '旅程进度',
  ratio: '画面',
  recordStart: '开始录制',
  safeArea: '为社交平台留边',
  /** A control's hint with its key: 播放（空格）. */
  withKey: '{action}（{key}）',

  // The shortcut panel
  keyEnd: 'End',
  keyEscape: 'Esc',
  keyHome: 'Home',
  keyShift: 'Shift',
  keySpace: '空格',
  shortcutCards: '上一张 / 下一张卡片',
  shortcutDays: '上一天 / 下一天',
  shortcutEnds: '回到开头 / 跳到回顾',
  shortcutEscape: '退出放映 / 关闭回放',
  shortcutExport: '导出视频',
  shortcutFullscreen: '全屏放映',
  shortcutHelp: '显示快捷键',
  shortcutMusic: '背景音乐',
  shortcutPhotos: '上一张 / 下一张照片',
  shortcutPlay: '播放 / 暂停',
  shortcutSpeed: '减速 / 加速',
  shortcuts: '快捷键',
  shortcutsPlayback: '播放',
  shortcutsPresenting: '放映与导出',
  shortcutsSpeedSound: '速度与声音',

  // Weather on the day cards
  weatherClear: '晴',
  weatherDrizzle: '毛毛雨',
  weatherFog: '雾',
  weatherLabel: '{kind} · {min}–{max}°C',
  weatherOvercast: '阴',
  weatherPartlyCloudy: '多云',
  weatherRain: '雨',
  weatherShowers: '阵雨',
  weatherSnow: '雪',
  weatherSnowShowers: '阵雪',
  weatherThunderstorm: '雷雨',
}

export type Messages = typeof zhCN
export type MessageKey = keyof Messages

/** The defaults with `overrides` in place of some of them. */
export function resolveMessages(overrides?: Partial<Messages>): Messages {
  return { ...zhCN, ...overrides }
}

/** A message with its `{slots}` filled in. */
export function t(
  messages: Messages,
  key: MessageKey,
  params: Record<string, string | number> = {},
) {
  return messages[key].replaceAll(/\{(\w+)\}/g, (slot, name: string) =>
    name in params ? String(params[name]) : slot,
  )
}
