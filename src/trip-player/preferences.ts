// Per-viewer playback choices, remembered in this browser when storage allows.

const MUSIC_PREFERENCE_KEY = 'calendar-trip-music'
const SPEED_PREFERENCE_KEY = 'calendar-trip-speed'

export const SPEEDS = [1, 1.5, 2] as const

export function readMusicPreference() {
  try {
    return localStorage.getItem(MUSIC_PREFERENCE_KEY) === 'on'
  } catch {
    return false
  }
}

export function writeMusicPreference(on: boolean) {
  try {
    localStorage.setItem(MUSIC_PREFERENCE_KEY, on ? 'on' : 'off')
  } catch {
    // The choice just won't be remembered.
  }
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
