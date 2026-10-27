// Per-viewer playback choices, remembered in this browser when storage allows.

import { parseMapSettings, type MapSettings } from '../map/map-settings.ts'

const MUSIC_PREFERENCE_KEY = 'trip-map-music'
const SPEED_PREFERENCE_KEY = 'trip-map-speed'
const SAFE_AREA_PREFERENCE_KEY = 'trip-map-safe-area'
const RATIO_PREFERENCE_KEY = 'trip-map-ratio'
const FLYOVER_PREFERENCE_KEY = 'trip-map-flyover'

export const SPEEDS = [1, 1.5, 2] as const

export function readMusicPreference() {
  return readSwitch(MUSIC_PREFERENCE_KEY, false)
}

export function writeMusicPreference(on: boolean) {
  writeSwitch(MUSIC_PREFERENCE_KEY, on)
}

/** Whether playback flies over the route, low and turned the way it heads; off by default. */
export function readFlyoverPreference() {
  return readSwitch(FLYOVER_PREFERENCE_KEY, false)
}

export function writeFlyoverPreference(on: boolean) {
  writeSwitch(FLYOVER_PREFERENCE_KEY, on)
}

export function readSpeedPreference(): number {
  try {
    const value = Number(localStorage.getItem(SPEED_PREFERENCE_KEY))
    return SPEEDS.find((speed) => speed === value) ?? 1
  } catch {
    return 1
  }
}

export function writeSpeedPreference(speed: number) {
  try {
    localStorage.setItem(SPEED_PREFERENCE_KEY, String(speed))
  } catch {
    // The choice just won't be remembered.
  }
}

/** The speed after `speed` in the 1× → 1.5× → 2× cycle. */
export function getNextSpeed(speed: number) {
  const index = SPEEDS.findIndex((item) => item === speed)
  return SPEEDS[(index + 1) % SPEEDS.length]!
}

/** Whether a portrait video leaves room for a short-video app's own overlays; on by default. */
export function readSafeAreaPreference() {
  return readSwitch(SAFE_AREA_PREFERENCE_KEY, true)
}

export function writeSafeAreaPreference(on: boolean) {
  writeSwitch(SAFE_AREA_PREFERENCE_KEY, on)
}

/** The last layout chosen for presenting or recording; portrait suits phones best. */
export function readRatioPreference(): 'landscape' | 'portrait' {
  try {
    return localStorage.getItem(RATIO_PREFERENCE_KEY) === 'landscape' ? 'landscape' : 'portrait'
  } catch {
    return 'portrait'
  }
}

export function writeRatioPreference(ratio: 'landscape' | 'portrait') {
  try {
    localStorage.setItem(RATIO_PREFERENCE_KEY, ratio)
  } catch {
    // The choice just won't be remembered.
  }
}

const FOG_PREFERENCE_KEY = 'trip-map-fog'

/** Whether the map is under its fog of war; off by default. */
export function readFogPreference() {
  return readSwitch(FOG_PREFERENCE_KEY, false)
}

export function writeFogPreference(on: boolean) {
  writeSwitch(FOG_PREFERENCE_KEY, on)
}

const MAP_SETTINGS_PREFERENCE_KEY = 'trip-map-settings'

/** The map's settings as last set, if they ever were. */
export function readMapSettingsPreference(): MapSettings | undefined {
  try {
    const value = localStorage.getItem(MAP_SETTINGS_PREFERENCE_KEY)
    return value ? parseMapSettings(JSON.parse(value)) : undefined
  } catch {
    return undefined
  }
}

export function writeMapSettingsPreference(settings: MapSettings) {
  try {
    localStorage.setItem(MAP_SETTINGS_PREFERENCE_KEY, JSON.stringify(settings))
  } catch {
    // The choice just won't be remembered.
  }
}

function readSwitch(key: string, fallback: boolean) {
  try {
    // Only the other choice, stored, changes it.
    return localStorage.getItem(key) === (fallback ? 'off' : 'on') ? !fallback : fallback
  } catch {
    return fallback
  }
}

function writeSwitch(key: string, on: boolean) {
  try {
    localStorage.setItem(key, on ? 'on' : 'off')
  } catch {
    // The choice just won't be remembered.
  }
}
